package org.fabt.shared.api;

/**
 * Interface for cleaning up held reservations during test reset.
 * Defined in the shared kernel so TestResetController can depend on it
 * without violating module boundaries. Implemented by the reservation module.
 *
 * @return number of reservations cancelled
 */
public interface HeldReservationCleaner {

    int cancelAllHeldReservations();
}
