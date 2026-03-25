package org.fabt.hmis.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.hmis.domain.HmisOutboxEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class HmisOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<HmisOutboxEntry> ROW_MAPPER = (rs, rowNum) -> {
        HmisOutboxEntry e = new HmisOutboxEntry();
        e.setId(rs.getObject("id", UUID.class));
        e.setTenantId(rs.getObject("tenant_id", UUID.class));
        e.setShelterId(rs.getObject("shelter_id", UUID.class));
        e.setVendorType(rs.getString("vendor_type"));
        e.setStatus(rs.getString("status"));
        e.setPayload(rs.getString("payload"));
        e.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);
        e.setSentAt(rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null);
        e.setErrorMessage(rs.getString("error_message"));
        e.setRetryCount(rs.getInt("retry_count"));
        return e;
    };

    public HmisOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HmisOutboxEntry insert(HmisOutboxEntry entry) {
        List<HmisOutboxEntry> results = jdbcTemplate.query(
                """
                INSERT INTO hmis_outbox (tenant_id, shelter_id, vendor_type, status, payload, created_at, retry_count)
                VALUES (?, ?, ?, ?, ?, clock_timestamp(), 0)
                RETURNING *
                """,
                ROW_MAPPER,
                entry.getTenantId(), entry.getShelterId(), entry.getVendorType(),
                entry.getStatus(), entry.getPayload());
        return results.get(0);
    }

    public List<HmisOutboxEntry> findPending() {
        return jdbcTemplate.query(
                "SELECT * FROM hmis_outbox WHERE status = 'PENDING' ORDER BY created_at",
                ROW_MAPPER);
    }

    public List<HmisOutboxEntry> findDeadLetterByTenantId(UUID tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM hmis_outbox WHERE tenant_id = ? AND status = 'DEAD_LETTER' ORDER BY created_at DESC",
                ROW_MAPPER, tenantId);
    }

    public void updateStatus(UUID id, String status, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE hmis_outbox SET status = ?, sent_at = clock_timestamp(), error_message = ?, retry_count = retry_count + 1 WHERE id = ?",
                status, errorMessage, id);
    }

    public void resetToPending(UUID id) {
        jdbcTemplate.update(
                "UPDATE hmis_outbox SET status = 'PENDING', error_message = NULL WHERE id = ?", id);
    }

    public int countDeadLetter() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hmis_outbox WHERE status = 'DEAD_LETTER'", Integer.class);
        return count != null ? count : 0;
    }

    public Optional<HmisOutboxEntry> findById(UUID id) {
        List<HmisOutboxEntry> results = jdbcTemplate.query(
                "SELECT * FROM hmis_outbox WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
