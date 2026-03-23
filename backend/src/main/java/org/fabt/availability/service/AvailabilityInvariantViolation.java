package org.fabt.availability.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a bed availability snapshot would violate one of the 9 invariants
 * defined in the QA briefing (bed-availability-qa-briefing.md).
 *
 * INV-1: beds_available >= 0
 * INV-2: beds_occupied <= beds_total
 * INV-3: beds_on_hold <= (beds_total - beds_occupied)
 * INV-4: beds_total >= 0
 * INV-5: beds_occupied + beds_on_hold <= beds_total
 *
 * Returns 422 Unprocessable Entity via @ResponseStatus.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class AvailabilityInvariantViolation extends RuntimeException {

    public AvailabilityInvariantViolation(String message) {
        super(message);
    }
}
