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
        r.setIdempotencyKey(rs.getString("idempotency_key"));
        return r;
    };

    public ReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Reservation insert(Reservation reservation) {
        List<Reservation> results = jdbcTemplate.query(
                """
                INSERT INTO reservation (shelter_id, tenant_id, population_type, user_id, status, expires_at, notes, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                ROW_MAPPER,
                reservation.getShelterId(),
                reservation.getTenantId(),
                reservation.getPopulationType(),
                reservation.getUserId(),
                reservation.getStatus().name(),
                Timestamp.from(reservation.getExpiresAt()),
                reservation.getNotes(),
                reservation.getIdempotencyKey()
        );
        return results.isEmpty() ? reservation : results.get(0);
    }

    public Optional<Reservation> findActiveByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE user_id = ? AND idempotency_key = ? AND status = 'HELD'",
                ROW_MAPPER,
                userId, idempotencyKey
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
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

    /**
     * Extended user-reservation lookup for the Phase 3 "My Past Holds" view
     * (notification-deep-linking task 8.1). Filters by status list AND age
     * in days so an outreach worker can see HELD + terminal (CANCELLED,
     * EXPIRED, CONFIRMED, CANCELLED_SHELTER_DEACTIVATED) rows from the last
     * 14 days grouped by status. Both filters are optional — null means
     * "no filter on this dimension" — so existing callers that only need
     * HELD can keep using {@link #findActiveByUserId}.
     *
     * <p>PostgreSQL {@code status = ANY(?)} accepts a {@code String[]} and
     * produces the same SET-style match as an IN clause without parameter
     * expansion gymnastics. The {@code sinceDays} filter uses
     * {@code NOW() - make_interval(days => ?)} so the caller passes a plain
     * integer and the server computes the threshold (avoids client-clock
     * skew for multi-day windows).</p>
     *
     * <p>Results ordered by {@code created_at DESC} so the freshest rows
     * appear first within each status group — matches the coordinator
     * dashboard's ordering of the active HELD list.</p>
     */
    public List<Reservation> findByUserIdAndStatusesSince(
            UUID tenantId,
            UUID userId,
            String[] statuses,
            Integer sinceDays) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM reservation WHERE tenant_id = ? AND user_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (statuses != null && statuses.length > 0) {
            sql.append(" AND status = ANY(?)");
            args.add(statuses);
        }
        if (sinceDays != null) {
            sql.append(" AND created_at > NOW() - make_interval(days => ?)");
            args.add(sinceDays);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
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

    public List<Reservation> findHeldByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE shelter_id = ? AND status = 'HELD'",
                ROW_MAPPER,
                shelterId
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
            case CANCELLED_SHELTER_DEACTIVATED -> "cancelled_at";
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
