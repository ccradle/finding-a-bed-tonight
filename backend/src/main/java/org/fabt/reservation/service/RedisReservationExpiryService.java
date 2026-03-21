package org.fabt.reservation.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Redis-accelerated reservation expiry for Standard/Full tiers.
 *
 * On reservation create: sets a Redis key with TTL equal to hold duration.
 * On key expiry: Redis keyspace notification triggers immediate expiry.
 * The scheduled task (ReservationExpiryService) still runs as a safety net.
 *
 * NOTE: Requires spring-boot-starter-data-redis and Redis keyspace
 * notification config (notify-keyspace-events Ex). Currently a placeholder
 * that logs intent — full Redis integration will be wired when the
 * Standard tier Redis dependency is added to the POM.
 */
@Service
@Profile({"standard", "full"})
public class RedisReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(RedisReservationExpiryService.class);

    private static final String KEY_PREFIX = "reservation:";

    /**
     * Called after a reservation is created to set a Redis TTL key.
     * When the key expires, the keyspace notification triggers expireReservation().
     */
    public void scheduleExpiry(UUID reservationId, long ttlSeconds) {
        // TODO: Wire RedisTemplate when spring-boot-starter-data-redis is added
        // redisTemplate.opsForValue().set(KEY_PREFIX + reservationId, "1", Duration.ofSeconds(ttlSeconds));
        log.debug("Redis expiry scheduled for reservation {} in {} seconds (placeholder — Redis not yet wired)",
                reservationId, ttlSeconds);
    }

    /**
     * Called to cancel a pending Redis expiry (e.g., on confirm or cancel).
     */
    public void cancelExpiry(UUID reservationId) {
        // TODO: Wire RedisTemplate when spring-boot-starter-data-redis is added
        // redisTemplate.delete(KEY_PREFIX + reservationId);
        log.debug("Redis expiry cancelled for reservation {} (placeholder — Redis not yet wired)", reservationId);
    }
}
