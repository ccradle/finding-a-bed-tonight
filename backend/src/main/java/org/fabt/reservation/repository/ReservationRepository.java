package org.fabt.reservation.repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Reservation> ROW_MAPPER = (rs, rowNum) -> {
        Reservation r = new Reservation();
        r.setId(rs.getObject("id", UUID.class));
        r.setShelterId(rs.getObject("shelter_id", UUID.class));
        r.setTenantId(rs.getObject("tenant_id", UUID.class));
        r.setPopulationType(rs.getString("population_type"));
        r.setUserId(rs.getObject("user_id", UUID.class));
        r.setStatus(ReservationStatus.valueOf(rs.getString("status")));
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        r.setExpiresAt(expiresAt != null ? expiresAt.toInstant() : null);
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        r.setConfirmedAt(confirmedAt != null ? confirmedAt.toInstant() : null);
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        r.setCancelledAt(cancelledAt != null ? cancelledAt.toInstant() : null);
        Timestamp createdAt = rs.getTimestamp("created_at");
        r.setCreatedAt(createdAt != null ? createdAt.toInstant() : null);
        r.setNotes(rs.getString("notes"));
        return r;
    };

    public ReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Reservation insert(Reservation reservation) {
        List<Reservation> results = jdbcTemplate.query(
                """
                INSERT INTO reservation (shelter_id, tenant_id, population_type, user_id, status, expires_at, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                ROW_MAPPER,
                reservation.getShelterId(),
                reservation.getTenantId(),
                reservation.getPopulationType(),
                reservation.getUserId(),
                reservation.getStatus().name(),
                Timestamp.from(reservation.getExpiresAt()),
                reservation.getNotes()
        );
        return results.isEmpty() ? reservation : results.get(0);
    }

    public Optional<Reservation> findByIdAndTenantId(UUID id, UUID tenantId) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE id = ? AND tenant_id = ?",
                ROW_MAPPER,
                id, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Reservation> findById(UUID id) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE id = ?",
                ROW_MAPPER,
                id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Reservation> findActiveByUserId(UUID tenantId, UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE tenant_id = ? AND user_id = ? AND status = 'HELD' ORDER BY created_at DESC",
                ROW_MAPPER,
                tenantId, userId
        );
    }

    public List<Reservation> findActiveByShelterId(UUID shelterId, String populationType) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE shelter_id = ? AND population_type = ? AND status = 'HELD'",
                ROW_MAPPER,
                shelterId, populationType
        );
    }

    public List<Reservation> findAllHeld() {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE status = 'HELD'",
                ROW_MAPPER
        );
    }

    public List<Reservation> findExpired() {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE status = 'HELD' AND expires_at < NOW()",
                ROW_MAPPER
        );
    }

    /**
     * Optimistic update: only transitions from HELD to the target status.
     * Returns the number of rows affected (0 = already transitioned, 1 = success).
     */
    public int updateStatus(UUID id, ReservationStatus newStatus) {
        String timestampCol = switch (newStatus) {
            case CONFIRMED -> "confirmed_at";
            case CANCELLED -> "cancelled_at";
            case EXPIRED -> "cancelled_at"; // reuse cancelled_at for expired timestamp
            default -> null;
        };

        if (timestampCol != null) {
            return jdbcTemplate.update(
                    "UPDATE reservation SET status = ?, " + timestampCol + " = NOW() WHERE id = ? AND status = 'HELD'",
                    newStatus.name(), id
            );
        }
        return jdbcTemplate.update(
                "UPDATE reservation SET status = ? WHERE id = ? AND status = 'HELD'",
                newStatus.name(), id
        );
    }

    /**
     * Acquires a PostgreSQL advisory lock for the shelter + population type combination.
     * Released automatically when the transaction ends.
     * Prevents concurrent hold creation on the same bed pool.
     */
    public void acquireAdvisoryLock(UUID shelterId, String populationType) {
        // Hash shelter ID + population type into a long for pg_advisory_xact_lock
        long lockKey = (shelterId.hashCode() * 31L) + populationType.hashCode();
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
    }

    public int countActiveByShelterId(UUID shelterId, String populationType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE shelter_id = ? AND population_type = ? AND status = 'HELD'",
                Integer.class,
                shelterId, populationType
        );
        return count != null ? count : 0;
    }
}
