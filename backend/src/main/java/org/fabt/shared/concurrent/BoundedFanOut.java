package org.fabt.shared.concurrent;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semaphore-bounded fan-out using virtual threads. Each task runs on its own
 * virtual thread with TenantContext bound via ScopedValue. Concurrency is limited
 * to prevent connection pool exhaustion.
 */
public final class BoundedFanOut {

    private static final Logger log = LoggerFactory.getLogger(BoundedFanOut.class);

    private BoundedFanOut() {}

    /**
     * Fan out a per-tenant action across all tenants, bounded by maxConcurrency.
     * Each action runs on a virtual thread with the tenant's context bound.
     * Blocks until all tasks complete.
     *
     * @param tenantIds      tenants to process
     * @param dvAccess       DV access level for all fan-out threads
     * @param maxConcurrency maximum concurrent tasks (should be <= connection pool size minus headroom)
     * @param action         the per-tenant action to execute
     */
    public static void forEachTenant(Collection<UUID> tenantIds, boolean dvAccess,
                                      int maxConcurrency, Consumer<UUID> action) {
        Semaphore semaphore = new Semaphore(maxConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID tenantId : tenantIds) {
                semaphore.acquire();
                executor.submit(() -> {
                    try {
                        TenantContext.runWithContext(tenantId, dvAccess, () ->
                            action.accept(tenantId)
                        );
                    } catch (Exception e) {
                        log.warn("Fan-out task failed for tenant {}: {}", tenantId, e.getMessage());
                    } finally {
                        semaphore.release();
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Fan-out interrupted with {} tenants remaining", tenantIds.size());
        }
    }
}
