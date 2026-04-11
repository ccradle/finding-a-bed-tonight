package org.fabt.notification.repository;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.fabt.notification.domain.EscalationPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@code escalation_policy} table.
 *
 * <p><b>Append-only.</b> Read methods only — no {@code update(...)} or
 * {@code delete(...)}. New versions are inserted via {@link #insertNewVersion}
 * which never modifies an existing row. The Flyway V40 migration enforces
 * this at the DB layer with no UPDATE or DELETE policies.</p>      
 *
 * <p><b>Why JdbcTemplate instead of Spring Data JDBC {@code CrudRepository}:</b>
 * the {@code thresholds} column is JSONB containing a list of nested records,
 * which Spring Data JDBC's auto-mapping does not handle cleanly. JdbcTemplate
 * gives us full control over the row mapping — we deserialize the JSONB string
 * with Jackson and construct the {@code EscalationPolicy} record explicitly.
 * Same pattern as {@link NotificationRepository#batchInsert} for the
 * {@code payload} JSONB column.</p>
 */
@Repository
public class EscalationPolicyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EscalationPolicyRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Find a policy by its primary key. Used by the escalation batch job to
     * resolve the frozen policy that a referral was snapshotted against.
     */
    public Optional<EscalationPolicy> findById(UUID id) {
        try {
            EscalationPolicy policy = jdbcTemplate.queryForObject(  
                    """
                    SELECT id, tenant_id, event_type, version, thresholds, created_at, created_by
                      FROM escalation_policy
                     WHERE id = ?
                    """,
                    rowMapper(),
                    id);
            return Optional.ofNullable(policy);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find the current (max-version) policy for a tenant + event type. The
     * tenant_id parameter may be null to find the platform default policy.
     *
     * <p>This method is called by {@code ReferralTokenService.create()} to
     * snapshot the current policy onto a new referral. If the tenant has no
     * custom policy yet, the caller is expected to fall back to    
     * {@link #findCurrentPlatformDefault} via {@code EscalationPolicyService}.</p>
     */
    public Optional<EscalationPolicy> findCurrentByTenantAndEventType(UUID tenantId, String eventType) {
        try {
            EscalationPolicy policy = jdbcTemplate.queryForObject(  
                    """
                    SELECT id, tenant_id, event_type, version, thresholds, created_at, created_by
                      FROM escalation_policy
                     WHERE tenant_id IS NOT DISTINCT FROM ?
                       AND event_type = ?
                     ORDER BY version DESC
                     LIMIT 1
                    """,
                    rowMapper(),
                    tenantId,
                    eventType);
            return Optional.ofNullable(policy);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find the current platform default policy for an event type. Used as a
     * fallback when a tenant has no custom policy AND for backwards
     * compatibility with {@code referral_token} rows whose
     * {@code escalation_policy_id} is NULL (existing rows from before V41).
     */
    public Optional<EscalationPolicy> findCurrentPlatformDefault(String eventType) {
        return findCurrentByTenantAndEventType(null, eventType);    
    }

    /**
     * Insert a new version row. The new version number is computed atomically
     * in the database via subquery to prevent race conditions (Riley Cho's fix).
     *
     * @return the newly created policy with its assigned id and version
     */
    public EscalationPolicy insertNewVersion(UUID tenantId, String eventType,
                                              List<EscalationPolicy.Threshold> thresholds,
                                              UUID createdBy) {     
        UUID newId = UUID.randomUUID();
        String thresholdsJson = serializeThresholds(thresholds);    

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(     
                    """
                    INSERT INTO escalation_policy
                      (id, tenant_id, event_type, version, thresholds, created_by)
                    VALUES
                      (?, ?, ?, (
                        SELECT COALESCE(MAX(version), 0) + 1 
                        FROM escalation_policy 
                        WHERE tenant_id IS NOT DISTINCT FROM ? 
                          AND event_type = ?
                      ), CAST(? AS jsonb), ?)
                    """);
            ps.setObject(1, newId);
            ps.setObject(2, tenantId);
            ps.setString(3, eventType);
            ps.setObject(4, tenantId);
            ps.setString(5, eventType);
            ps.setString(6, thresholdsJson);
            ps.setObject(7, createdBy);
            return ps;
        });

        // Re-read to get the database-assigned created_at timestamp and version.
        return findById(newId)
                .orElseThrow(() -> new IllegalStateException(       
                        "Just-inserted policy not found by id — concurrent delete?"));
    }

    private RowMapper<EscalationPolicy> rowMapper() {
        return (rs, rowNum) -> new EscalationPolicy(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("version"),
                deserializeThresholds(rs.getString("thresholds")),  
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class));
    }

    private List<EscalationPolicy.Threshold> deserializeThresholds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            // The JSONB column stores: [{"at":"PT1H","severity":"...","recipients":[...]}, ...]
            // Duration values are ISO-8601 strings; deserialize as List<Map> then construct records.
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(this::toThreshold)
                    .toList();
        } catch (JacksonException e) {
            throw new DataAccessException("Failed to deserialize escalation_policy.thresholds JSONB: " + e.getMessage(), e) {};
        }
    }

    private EscalationPolicy.Threshold toThreshold(Map<String, Object> raw) {
        try {
            String id = (String) raw.get("id");
            Duration at = Duration.parse((String) raw.get("at"));
            String severity = (String) raw.get("severity");
            @SuppressWarnings("unchecked")
            List<String> recipients = (List<String>) raw.get("recipients");
            return new EscalationPolicy.Threshold(id, at, severity, recipients);
        } catch (DateTimeParseException | ClassCastException | NullPointerException e) {
            throw new IllegalStateException(
                    "escalation_policy.thresholds JSONB row has invalid shape: " + raw, e);
        }
    }

    private String serializeThresholds(List<EscalationPolicy.Threshold> thresholds) {
        try {
            List<Map<String, Object>> raw = thresholds.stream()
                    .map(t -> Map.<String, Object>of(
                            "id", t.id(),
                            "at", t.at().toString(),
                            "severity", t.severity(),
                            "recipients", t.recipients()))
                    .toList();
            return objectMapper.writeValueAsString(raw);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize escalation_policy.thresholds: " + e.getMessage(), e);
        }
    }
}
