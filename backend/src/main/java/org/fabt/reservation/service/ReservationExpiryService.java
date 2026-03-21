package org.fabt.reservation.service;

import java.util.List;

import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Lite-tier auto-expiry: polls for expired HELD reservations every 30 seconds.
 * In Standard/Full tiers, Redis TTL provides near-instant expiry and this
 * scheduled task serves as a safety net for missed notifications.
 */
@Service
public class ReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;

    public ReservationExpiryService(ReservationRepository reservationRepository,
                                     ReservationService reservationService) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedRate = 30_000)
    public void expireOverdueReservations() {
        List<Reservation> expired = reservationRepository.findExpired();
        if (expired.isEmpty()) {
            return;
        }

        log.info("Found {} expired reservations to process", expired.size());

        for (Reservation reservation : expired) {
            try {
                reservationService.expireReservation(reservation.getId());
            } catch (Exception e) {
                log.error("Failed to expire reservation {}: {}", reservation.getId(), e.getMessage());
            }
        }
    }
}
