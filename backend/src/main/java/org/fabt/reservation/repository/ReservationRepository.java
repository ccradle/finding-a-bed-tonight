package org.fabt.reservation.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.domain.ReservationStatus;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SecretEncryptionService encryptionService;

    /**
     * Row mapper instance — was {@code static final} before transitional-
     * reentry-support task 3.5. Now an instance field because the V93
     * {@code held_for_client_*_encrypted} columns require a Spring-managed
     * {@link SecretEncryptionService} for decryption. Closes over the
     * injected service. Null-safe for all encrypted columns: a null DB
     * value maps to a null entity field with no decrypt attempt.
     */
    private final RowMapper<Reservation> rowMapper;

    public ReservationRepository(JdbcTemplate jdbcTemplate, SecretEncryptionService encryptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.encryptionService = encryptionService;
        this.rowMapper = (rs, rowNum) -> {
            Reservation r = new Reservation();
            r.setId(rs.getObject("id", UUID.class));
            r.setShelterId(rs.getObject("shelter_id", UUID.class));
            UUID tenantId = rs.getObject("tenant_id", UUID.class);
            r.setTenantId(tenantId);
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

            // V93 PII fields — decrypt only if both column and tenant_id present.
            // tenant_id should always be non-null per the table NOT NULL constraint
            // but the defensive check costs nothing and prevents a confusing NPE
            // if a future migration relaxes the NOT NULL.
            String nameEnc = rs.getString("held_for_client_name_encrypted");
            if (nameEnc != null && tenantId != null) {
                r.setHeldForClientName(
                    encryptionService.decryptForTenant(tenantId, KeyPurpose.RESERVATION_PII, nameEnc));
            }
            String dobEnc = rs.getString("held_for_client_dob_encrypted");
            if (dobEnc != null && tenantId != null) {
                String dobIso = encryptionService.decryptForTenant(tenantId, KeyPurpose.RESERVATION_PII, dobEnc);
                r.setHeldForClientDob(LocalDate.parse(dobIso));
            }
            String notesEnc = rs.getString("hold_notes_encrypted");
            if (notesEnc != null && tenantId != null) {
                r.setHoldNotes(
                    encryptionService.decryptForTenant(tenantId, KeyPurpose.RESERVATION_PII, notesEnc));
            }
            return r;
        };
    }

    public Reservation insert(Reservation reservation) {
        // V93 hold-attribution PII: encrypt before INSERT so plaintext never
        // touches disk (design D4 two-layer posture). Null inputs stay null —
        // no encrypt attempt. tenant_id MUST be set on the entity before insert
        // (existing precondition; the encrypt path additionally relies on it).
        UUID tenantId = reservation.getTenantId();
        String nameEnc = encryptIfPresent(tenantId, reservation.getHeldForClientName());
        String dobEnc = encryptIfPresent(tenantId,
            reservation.getHeldForClientDob() != null ? reservation.getHeldForClientDob().toString() : null);
        String notesEnc = encryptIfPresent(tenantId, reservation.getHoldNotes());

        List<Reservation> results = jdbcTemplate.query(
                """
                INSERT INTO reservation (
                    shelter_id, tenant_id, population_type, user_id, status, expires_at,
                    notes, idempotency_key,
                    held_for_client_name_encrypted, held_for_client_dob_encrypted, hold_notes_encrypted
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                rowMapper,
                reservation.getShelterId(),
                tenantId,
                reservation.getPopulationType(),
                reservation.getUserId(),
                reservation.getStatus().name(),
                Timestamp.from(reservation.getExpiresAt()),
                reservation.getNotes(),
                reservation.getIdempotencyKey(),
                nameEnc, dobEnc, notesEnc
        );
        return results.isEmpty() ? reservation : results.get(0);
    }

    /**
     * Helper: encrypt with RESERVATION_PII purpose if the plaintext is
     * non-null and the tenant id is non-null. Returns null otherwise so
     * the SQL receives an honest NULL parameter (no empty-string sentinel).
     */
    private String encryptIfPresent(UUID tenantId, String plaintext) {
        if (plaintext == null || tenantId == null) return null;
        return encryptionService.encryptForTenant(tenantId, KeyPurpose.RESERVATION_PII, plaintext);
    }

    public Optional<Reservation> findActiveByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE user_id = ? AND idempotency_key = ? AND status = 'HELD'",
                rowMapper,
                userId, idempotencyKey
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Reservation> findByIdAndTenantId(UUID id, UUID tenantId) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE id = ? AND tenant_id = ?",
                rowMapper,
                id, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Phase F §D3 active-state guard. Returns the reservation only if the owning
     * tenant is ACTIVE; non-ACTIVE tenants' reservations appear as empty (caller maps
     * to 404 — no existence leak). Preferred over {@link #findByIdAndTenantId} for
     * request-bound paths.
     */
    public Optional<Reservation> findByIdAndActiveTenantId(UUID id, UUID tenantId) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT r.* FROM reservation r "
                + "INNER JOIN tenant t ON t.id = r.tenant_id "
                + "WHERE r.id = ? AND r.tenant_id = ? AND t.state = 'ACTIVE'",
                rowMapper,
                id, tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Reservation> findById(UUID id) {
        List<Reservation> results = jdbcTemplate.query(
                "SELECT * FROM reservation WHERE id = ?",
                rowMapper,
                id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Reservation> findActiveByUserId(UUID tenantId, UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE tenant_id = ? AND user_id = ? AND status = 'HELD' ORDER BY created_at DESC",
                rowMapper,
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
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public List<Reservation> findActiveByShelterId(UUID shelterId, String populationType) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE shelter_id = ? AND population_type = ? AND status = 'HELD'",
                rowMapper,
                shelterId, populationType
        );
    }

    public List<Reservation> findAllHeld() {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE status = 'HELD'",
                rowMapper
        );
    }

    public List<Reservation> findHeldByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE shelter_id = ? AND status = 'HELD'",
                rowMapper,
                shelterId
        );
    }

    public List<Reservation> findExpired() {
        return jdbcTemplate.query(
                "SELECT * FROM reservation WHERE status = 'HELD' AND expires_at < NOW()",
                rowMapper
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
