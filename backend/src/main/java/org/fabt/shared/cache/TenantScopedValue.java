package org.fabt.shared.cache;

import java.util.UUID;

/**
 * Envelope that stamps every cache value with the tenant that wrote it.
 *
 * <p>Enables on-read tenant verification per
 * {@code docs/architecture/redis-pooling-adr.md} "Cached-value tenant
 * verification" — a second isolation control alongside the key prefix.
 * Prefix defends the read side (reader cannot guess another tenant's keys);
 * stamp-and-verify defends the write side (a caller with wrong TenantContext
 * bound cannot silently poison another tenant's keyspace). Both must fail
 * in the same direction for a cross-tenant leak.
 *
 * <p>The stamp is the {@link java.util.UUID} of the tenant bound at write
 * time (via {@code TenantContext.getTenantId()}), NOT a field extracted
 * from the payload. This is deliberate: a payload that itself contains a
 * {@code tenantId} field was populated by the same wrong-context caller
 * that will pass the wrapper's inner check. Stamping at the wrapper level
 * captures the execution-context tenant independently of whatever the
 * payload says.
 *
 * @param tenantId tenant context bound at write time
 * @param value caller's cached value (may be any serialisable type)
 */
public record TenantScopedValue<T>(UUID tenantId, T value) {}
