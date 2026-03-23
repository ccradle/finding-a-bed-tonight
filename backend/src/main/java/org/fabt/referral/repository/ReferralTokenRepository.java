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
        return t;
    };

    public ReferralTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReferralToken insert(ReferralToken token) {
        List<ReferralToken> results = jdbcTemplate.query(
                """
                INSERT INTO referral_token
                    (shelter_id, tenant_id, referring_user_id, household_size, population_type,
                     urgency, special_needs, callback_number, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)
                RETURNING *
                """,
                ROW_MAPPER,
                token.getShelterId(), token.getTenantId(), token.getReferringUserId(),
                token.getHouseholdSize(), token.getPopulationType(),
                token.getUrgency(), token.getSpecialNeeds(), token.getCallbackNumber(),
                token.getStatus(), Timestamp.from(token.getExpiresAt())
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

    public int expirePendingTokens() {
        return jdbcTemplate.update(
                "UPDATE referral_token SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at < NOW()");
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

    public int countPendingByShelterId(UUID shelterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_token WHERE shelter_id = ? AND status = 'PENDING'",
                Integer.class, shelterId);
        return count != null ? count : 0;
    }
}
