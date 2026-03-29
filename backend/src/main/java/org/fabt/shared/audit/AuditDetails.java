package org.fabt.shared.audit;

/**
 * Audit details for changes that track old and new values.
 */
public record AuditDetails(Object oldValue, Object newValue) {}
