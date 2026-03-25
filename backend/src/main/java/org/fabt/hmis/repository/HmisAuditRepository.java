package org.fabt.hmis.repository;

import java.util.List;
import java.util.UUID;

import org.fabt.hmis.domain.HmisAuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class HmisAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<HmisAuditEntry> ROW_MAPPER = (rs, rowNum) -> {
        HmisAuditEntry e = new HmisAuditEntry();
        e.setId(rs.getObject("id", UUID.class));
        e.setTenantId(rs.getObject("tenant_id", UUID.class));
        e.setVendorType(rs.getString("vendor_type"));
        e.setPushTimestamp(rs.getTimestamp("push_timestamp") != null ? rs.getTimestamp("push_timestamp").toInstant() : null);
        e.setRecordCount(rs.getInt("record_count"));
        e.setStatus(rs.getString("status"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setPayloadHash(rs.getString("payload_hash"));
        return e;
    };

    public HmisAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HmisAuditEntry insert(HmisAuditEntry entry) {
        List<HmisAuditEntry> results = jdbcTemplate.query(
                """
                INSERT INTO hmis_audit_log (tenant_id, vendor_type, push_timestamp, record_count, status, error_message, payload_hash)
                VALUES (?, ?, clock_timestamp(), ?, ?, ?, ?)
                RETURNING *
                """,
                ROW_MAPPER,
                entry.getTenantId(), entry.getVendorType(), entry.getRecordCount(),
                entry.getStatus(), entry.getErrorMessage(), entry.getPayloadHash());
        return results.get(0);
    }

    public List<HmisAuditEntry> findByTenantId(UUID tenantId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM hmis_audit_log WHERE tenant_id = ? ORDER BY push_timestamp DESC LIMIT ?",
                ROW_MAPPER, tenantId, limit);
    }

    public List<HmisAuditEntry> findByTenantIdAndVendor(UUID tenantId, String vendorType, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM hmis_audit_log WHERE tenant_id = ? AND vendor_type = ? ORDER BY push_timestamp DESC LIMIT ?",
                ROW_MAPPER, tenantId, vendorType, limit);
    }
}
