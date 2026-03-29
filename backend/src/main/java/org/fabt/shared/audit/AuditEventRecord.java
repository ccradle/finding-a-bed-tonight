package org.fabt.shared.audit;

import java.util.UUID;

/**
 * Audit event published via Spring ApplicationEventPublisher.
 * Lives in shared.audit so any module can publish without creating
 * a dependency on the auth module.
 */
public record AuditEventRecord(UUID actorUserId, UUID targetUserId, String action,
                                Object details, String ipAddress) {}
