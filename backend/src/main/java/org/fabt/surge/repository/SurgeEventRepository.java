package org.fabt.surge.repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.surge.domain.SurgeEvent;
import org.fabt.surge.domain.SurgeEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SurgeEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SurgeEvent> ROW_MAPPER = (rs, rowNum) -> {
        SurgeEvent e = new SurgeEvent();
        e.setId(rs.getObject("id", UUID.class));
        e.setTenantId(rs.getObject("tenant_id", UUID.class));
        e.setStatus(SurgeEventStatus.valueOf(rs.getString("status")));
        e.setReason(rs.getString("reason"));
        e.setBoundingBox(rs.getString("bounding_box"));
        e.setActivatedBy(rs.getObject("activated_by", UUID.class));
        Timestamp at = rs.getTimestamp("activated_at");
        e.setActivatedAt(at != null ? at.toInstant() : null);
        Timestamp dat = rs.getTimestamp("deactivated_at");
        e.setDeactivatedAt(dat != null ? dat.toInstant() : null);
        e.setDeactivatedBy(rs.getObject("deactivated_by", UUID.class));
        Timestamp se = rs.getTimestamp("scheduled_end");
        e.setScheduledEnd(se != null ? se.toInstant() : null);
        Timestamp ca = rs.getTimestamp("created_at");
        e.setCreatedAt(ca != null ? ca.toInstant() : null);
        return e;
    };

    public SurgeEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SurgeEvent insert(SurgeEvent event) {
        List<SurgeEvent> results = jdbcTemplate.query(
                """
                INSERT INTO surge_event (tenant_id, status, reason, bounding_box, activated_by, scheduled_end)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                RETURNING *
                """,
                ROW_MAPPER,
                event.getTenantId(), event.getStatus().name(), event.getReason(),
                event.getBoundingBox(), event.getActivatedBy(),
                event.getScheduledEnd() != null ? Timestamp.from(event.getScheduledEnd()) : null
        );
        return results.isEmpty() ? event : results.get(0);
    }

    public Optional<SurgeEvent> findByIdAndTenantId(UUID id, UUID tenantId) {
        List<SurgeEvent> results = jdbcTemplate.query(
                "SELECT * FROM surge_event WHERE id = ? AND tenant_id = ?",
                ROW_MAPPER, id, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<SurgeEvent> findActiveByTenantId(UUID tenantId) {
        List<SurgeEvent> results = jdbcTemplate.query(
                "SELECT * FROM surge_event WHERE tenant_id = ? AND status = 'ACTIVE' LIMIT 1",
                ROW_MAPPER, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<SurgeEvent> findByTenantId(UUID tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM surge_event WHERE tenant_id = ? ORDER BY activated_at DESC",
                ROW_MAPPER, tenantId
        );
    }

    public List<SurgeEvent> findExpired() {
        return jdbcTemplate.query(
                "SELECT * FROM surge_event WHERE status = 'ACTIVE' AND scheduled_end IS NOT NULL AND scheduled_end < NOW()",
                ROW_MAPPER
        );
    }

    public int updateStatus(UUID id, SurgeEventStatus newStatus, UUID deactivatedBy) {
        if (newStatus == SurgeEventStatus.DEACTIVATED || newStatus == SurgeEventStatus.EXPIRED) {
            return jdbcTemplate.update(
                    "UPDATE surge_event SET status = ?, deactivated_at = NOW(), deactivated_by = ? WHERE id = ? AND status = 'ACTIVE'",
                    newStatus.name(), deactivatedBy, id
            );
        }
        return jdbcTemplate.update(
                "UPDATE surge_event SET status = ? WHERE id = ? AND status = 'ACTIVE'",
                newStatus.name(), id
        );
    }
}
