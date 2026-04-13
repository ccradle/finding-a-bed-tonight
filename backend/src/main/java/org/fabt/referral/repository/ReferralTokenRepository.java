package org.fabt.referral.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.referral.domain.ReferralToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-based repository for DV referral tokens.
 * Zero client PII in all queries and results.
 */
@Repository
public class ReferralTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ReferralToken> ROW_MAPPER = (rs, rowNum) -> {
        ReferralToken t = new ReferralToken();
        t.setId(rs.getObject("id", UUID.class));
        t.setShelterId(rs.getObject("shelter_id", UUID.class));
        t.setShelterName(rs.getString("shelter_name"));
        t.setTenantId(rs.getObject("tenant_id", UUID.class));
        t.setReferringUserId(rs.getObject("referring_user_id", UUID.class));
        t.setHouseholdSize(rs.getInt("household_size"));
        t.setPopulationType(rs.getString("population_type"));
        t.setUrgency(rs.getString("urgency"));
        t.setSpecialNeeds(rs.getString("special_needs"));
        t.setCallbackNumber(rs.getString("callback_number"));
        t.setStatus(rs.getString("status"));
        t.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);
        t.setRespondedAt(rs.getTimestamp("responded_at") != null ? rs.getTimestamp("responded_at").toInstant() : null);
        t.setRespondedBy(rs.getObject("responded_by", UUID.class));
        t.setExpiresAt(rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null);
        t.setRejectionReason(rs.getString("rejection_reason"));
        t.setEscalationPolicyId(rs.getObject("escalation_policy_id", UUID.class));
        t.setClaimedByAdminId(rs.getObject("claimed_by_admin_id", UUID.class));
        t.setClaimExpiresAt(rs.getTimestamp("claim_expires_at") != null ? rs.getTimestamp("claim_expires_at").toInstant() : null);
        t.setEscalationChainBroken(rs.getBoolean("escalation_chain_broken"));
        return t;
    };

    public ReferralTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReferralToken insert(ReferralToken token) {
        List<ReferralToken> results = jdbcTemplate.query(
                """
                INSERT INTO referral_token
                    (shelter_id, shelter_name, tenant_id, referring_user_id, household_size, population_type,
                     urgency, special_needs, callback_number, status, created_at, expires_at,
                     escalation_policy_id, claimed_by_admin_id, claim_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?, ?)
                RETURNING *
                """,
                ROW_MAPPER,
                token.getShelterId(), token.getShelterName(), token.getTenantId(), token.getReferringUserId(),
                token.getHouseholdSize(), token.getPopulationType(),
                token.getUrgency(), token.getSpecialNeeds(), token.getCallbackNumber(),
                token.getStatus(), Timestamp.from(token.getExpiresAt()),
                token.getEscalationPolicyId(),
                token.getClaimedByAdminId(),
                token.getClaimExpiresAt() != null ? Timestamp.from(token.getClaimExpiresAt()) : null
        );
        return results.get(0);
    }

    public Optional<ReferralToken> findById(UUID id) {
        List<ReferralToken> results = jdbcTemplate.query(
                "SELECT * FROM referral_token WHERE id = ?",
                ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ReferralToken> findPendingByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                "SELECT * FROM referral_token WHERE shelter_id = ? AND status = 'PENDING' ORDER BY created_at",
                ROW_MAPPER, shelterId);
    }

    public List<ReferralToken> findByUserId(UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM referral_token WHERE referring_user_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, userId);
    }

    public void updateStatus(UUID id, String status, UUID respondedBy, String rejectionReason) {
        jdbcTemplate.update(
                "UPDATE referral_token SET status = ?, responded_at = clock_timestamp(), responded_by = ?, rejection_reason = ? WHERE id = ?",
                status, respondedBy, rejectionReason, id);
    }

    public List<UUID> expirePendingTokensReturningIds() {
        return jdbcTemplate.queryForList(
                "UPDATE referral_token SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at < NOW() RETURNING id",
                UUID.class);
    }

    public int purgeTerminalTokens(Instant olderThan) {
        return jdbcTemplate.update(
                """
                DELETE FROM referral_token
                WHERE status IN ('ACCEPTED', 'REJECTED') AND responded_at < ?
                   OR status = 'EXPIRED' AND expires_at < ?
                """,
                Timestamp.from(olderThan), Timestamp.from(olderThan));
    }

    public int countAllPending() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_token WHERE status = 'PENDING'",
                Integer.class);
        return count != null ? count : 0;
    }

    public List<ReferralToken> findAllPending() {
        return jdbcTemplate.query(
                "SELECT * FROM referral_token WHERE status = 'PENDING' ORDER BY created_at",
                ROW_MAPPER);
    }

    /**
     * Bounded variant of {@link #findAllPending} for the escalation tasklet.
     * The tasklet holds the entire result set in heap plus per-tenant lookup
     * caches; without a guardrail a runaway pending count would cause an OOM
     * on the {@code @Scheduled} thread (Sam Okafor R6 review point).
     *
     * <p>Returns at most {@code limit} rows ordered by {@code expires_at ASC}
     * so the most-urgent (about-to-expire) referrals are always processed
     * first. If the result hits {@code limit}, the next tasklet run picks up
     * the remainder — but anything past the cap risks missing its escalation
     * threshold by one batch interval, so urgency-first ordering minimizes
     * the operational impact (Sam Okafor + Riley Cho war-room call).</p>
     */
    public List<ReferralToken> findAllPending(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM referral_token WHERE status = 'PENDING' ORDER BY expires_at ASC LIMIT ?",
                ROW_MAPPER, limit);
    }

    /**
     * Get the escalated queue for admins (T-13), scoped to a single tenant.
     *
     * <p>The {@code tenant_id = ?} predicate is REQUIRED for two reasons:</p>
     * <ol>
     *   <li><b>Security (Marcus Webb):</b> referral_token RLS only checks
     *       {@code app.dv_access='true'} and does not isolate by tenant — the
     *       batch job needs platform-wide visibility, but an admin endpoint
     *       must scope to the caller's tenant or it leaks tenant B referrals
     *       to a tenant A admin.</li>
     *   <li><b>Performance (Sam Okafor / Elena Vasquez):</b> the V41 partial
     *       index is {@code (tenant_id, expires_at) WHERE status = 'PENDING'}
     *       — without {@code tenant_id =} as the leading predicate the planner
     *       cannot use it and falls back to a full heap scan + sort.</li>
     * </ol>
     */
    public List<ReferralToken> findEscalatedQueueByTenant(UUID tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM referral_token "
                + "WHERE tenant_id = ? AND status = 'PENDING' "
                + "ORDER BY expires_at ASC",
                ROW_MAPPER, tenantId);
    }

    public int countPendingByShelterIds(List<UUID> shelterIds) {
        if (shelterIds.isEmpty()) return 0;
        UUID[] ids = shelterIds.toArray(UUID[]::new);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_token WHERE shelter_id = ANY(?) AND status = 'PENDING'",
                Integer.class, (Object) ids);
        return count != null ? count : 0;
    }

    public int countPendingByShelterId(UUID shelterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_token WHERE shelter_id = ? AND status = 'PENDING'",
                Integer.class, shelterId);
        return count != null ? count : 0;
    }

    /**
     * Atomically attempt to claim a pending referral. Single conditional
     * UPDATE ... RETURNING * to close the TOCTOU window where two admins
     * both pass a Java-level read check and both write, AND to return the
     * winning row in the same round-trip (no follow-up SELECT).
     *
     * <p>The claim succeeds when the row is PENDING and one of:</p>
     * <ul>
     *   <li>no current claim ({@code claimed_by_admin_id IS NULL}),</li>
     *   <li>the current claim is expired ({@code claim_expires_at < NOW()}),</li>
     *   <li>the same admin is re-claiming (refresh / lock extension), or</li>
     *   <li>{@code override} is true (PagerDuty steal pattern).</li>
     * </ul>
     *
     * <p>Returns {@link Optional#empty} when the row is missing, no longer
     * PENDING, or someone else holds an unexpired claim and {@code override}
     * is false. Callers translate empty to {@code 409 Conflict}.</p>
     */
    public Optional<ReferralToken> tryClaim(UUID id, UUID adminId, Instant expiresAt, boolean override) {
        List<ReferralToken> rows = jdbcTemplate.query(
                "UPDATE referral_token "
                + "   SET claimed_by_admin_id = ?, claim_expires_at = ? "
                + " WHERE id = ? "
                + "   AND status = 'PENDING' "
                + "   AND (claimed_by_admin_id IS NULL "
                + "        OR claimed_by_admin_id = ? "
                + "        OR claim_expires_at < NOW() "
                + "        OR ?::boolean = true) "
                + "RETURNING *",
                ROW_MAPPER,
                adminId, Timestamp.from(expiresAt), id, adminId, override);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Release a claim only if the caller currently holds it (or {@code override}
     * is true). Returns row count for the same TOCTOU-safe pattern as
     * {@link #tryClaim}.
     */
    public int tryRelease(UUID id, UUID adminId, boolean override) {
        return jdbcTemplate.update(
                "UPDATE referral_token "
                + "   SET claimed_by_admin_id = NULL, claim_expires_at = NULL "
                + " WHERE id = ? "
                + "   AND claimed_by_admin_id IS NOT NULL "
                + "   AND (claimed_by_admin_id = ? OR ?::boolean = true)",
                id, adminId, override);
    }

    /**
     * Auto-release all claims that have expired across the platform. Used by
     * the @Scheduled cleanup task. The {@code claimed_by_admin_id IS NOT NULL}
     * predicate is required so the V41 partial index
     * {@code idx_referral_token_active_claim} is actually used by the planner
     * (Sam Okafor) — without it, the query degrades to a full table scan.
     *
     * <p>Returns one record per released claim, including the tenant_id so
     * the caller can publish SSE events with the correct tenant scope (the
     * {@code @Scheduled} thread has no inherited tenant context).</p>
     */
    public List<ReleasedClaim> clearExpiredClaims() {
        return jdbcTemplate.query(
                "UPDATE referral_token "
                + "   SET claimed_by_admin_id = NULL, claim_expires_at = NULL "
                + " WHERE claimed_by_admin_id IS NOT NULL "
                + "   AND claim_expires_at < NOW() "
                + "RETURNING id, tenant_id",
                (rs, rowNum) -> new ReleasedClaim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class)));
    }

    /**
     * Set the escalation_chain_broken flag on a referral. Only called from
     * the SPECIFIC_USER reassign path; other reassign target types leave the
     * flag FALSE so escalation continues normally.
     */
    public int markEscalationChainBroken(UUID id) {
        return jdbcTemplate.update(
                "UPDATE referral_token SET escalation_chain_broken = TRUE WHERE id = ?",
                id);
    }

    /**
     * Clear the escalation_chain_broken flag — the inverse of
     * {@link #markEscalationChainBroken}. Called from the COORDINATOR_GROUP
     * and COC_ADMIN_GROUP reassign branches so an admin can resume
     * auto-escalation after a SPECIFIC_USER reassign falls through (e.g. the
     * named user goes on PTO and the admin "gives the referral back to the
     * group"). Without this, the chain-broken state is sticky and silent —
     * Marcus Okafor's war-room round 4 finding.
     */
    public int markEscalationChainResumed(UUID id) {
        return jdbcTemplate.update(
                "UPDATE referral_token SET escalation_chain_broken = FALSE "
                + "WHERE id = ? AND escalation_chain_broken = TRUE",
                id);
    }

    /** Result row for {@link #clearExpiredClaims}. */
    public record ReleasedClaim(UUID id, UUID tenantId) {}
}
