package org.fabt.observability.anchor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.observability.batch.AuditChainAnchorJobConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tasklet-level test for {@link AuditChainAnchorJobConfig#anchorTenantsTasklet()}.
 *
 * <p>Closes Riley's must-fix gap from the G-3 warroom review: per-tenant loop,
 * zero-sentinel skip, per-tenant failure isolation, and counter-emission shape
 * were not previously covered. This test wires the JobConfig with mocked
 * dependencies (no Spring context, no DB) and invokes the Tasklet directly.
 *
 * <p>What it pins:
 * <ol>
 *   <li>Tenants with last_hash == zero-sentinel are SKIPPED (no upload call)</li>
 *   <li>Tenants with non-zero last_hash trigger {@code uploadAnchor} exactly once</li>
 *   <li>One tenant's failure does NOT abort the run for remaining tenants</li>
 *   <li>Success counter increments by tenants-uploaded count</li>
 *   <li>Failure counter is tagged by tenant_id</li>
 *   <li>tenants_anchored.count reports the success count, not the total</li>
 * </ol>
 */
@DisplayName("AuditChainAnchorJobConfig — tasklet per-tenant behavior")
class AuditChainAnchorJobTaskletTest {

    private static final byte[] ZERO_SENTINEL = new byte[32];
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TENANT_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ROW_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROW_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JdbcTemplate jdbc;
    private AuditChainAnchorService anchorService;
    private MeterRegistry meterRegistry;
    private BatchJobScheduler scheduler;
    private AuditChainAnchorJobConfig config;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        anchorService = mock(AuditChainAnchorService.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = mock(BatchJobScheduler.class);

        OciAuditAnchorProperties props = new OciAuditAnchorProperties(
                true, "us-ashburn-1",
                "ocid1.tenancy.oc1..test", "ocid1.user.oc1..test",
                "aa:bb", "/dev/null",
                "test-namespace", "test-bucket",
                "ocid1.compartment.oc1..test", null);

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meterRegistry);

        config = new AuditChainAnchorJobConfig(
                mock(JobRepository.class), jdbc, anchorService, scheduler, props, meterProvider);
    }

    @Test
    @DisplayName("zero-sentinel tenant is skipped; non-zero tenant uploads exactly once")
    void zeroSentinelSkippedNonZeroUploaded() throws Exception {
        byte[] realHash = nonZeroHash(0x42);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                rowMap(TENANT_A, ZERO_SENTINEL, null),     // skip
                rowMap(TENANT_B, realHash, ROW_B)          // upload
        ));

        Tasklet tasklet = config.anchorTenantsTasklet();
        tasklet.execute(null, null);

        verify(anchorService, never()).uploadAnchor(eq(TENANT_A), any(), any(), any(), any());
        verify(anchorService, times(1)).uploadAnchor(eq(TENANT_B), eq(realHash), eq(ROW_B), any(), any());

        assertThat(counterValue("fabt.audit.anchor.upload.count", "result", "success")).isEqualTo(1.0);
        assertThat(counterValue("fabt.audit.anchor.tenants_anchored.count", null, null)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one tenant's failure does not abort the run; failure counter tagged by tenant")
    void perTenantFailureIsolation() throws Exception {
        byte[] hashA = nonZeroHash(0x11);
        byte[] hashB = nonZeroHash(0x22);
        byte[] hashC = nonZeroHash(0x33);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                rowMap(TENANT_A, hashA, ROW_A),
                rowMap(TENANT_B, hashB, ROW_B),
                rowMap(TENANT_C, hashC, null)
        ));

        // Tenant B's upload throws; A and C succeed.
        lenient().when(anchorService.uploadAnchor(eq(TENANT_A), any(), any(), any(), any()))
                .thenReturn(new AuditChainAnchorService.AnchorUploadResult("k-a", "etag-a", "sha-a"));
        when(anchorService.uploadAnchor(eq(TENANT_B), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated OCI 503"));
        lenient().when(anchorService.uploadAnchor(eq(TENANT_C), any(), any(), any(), any()))
                .thenReturn(new AuditChainAnchorService.AnchorUploadResult("k-c", "etag-c", "sha-c"));

        Tasklet tasklet = config.anchorTenantsTasklet();
        tasklet.execute(null, null);

        verify(anchorService, times(1)).uploadAnchor(eq(TENANT_A), any(), any(), any(), any());
        verify(anchorService, times(1)).uploadAnchor(eq(TENANT_B), any(), any(), any(), any());
        verify(anchorService, atLeastOnce()).uploadAnchor(eq(TENANT_C), any(), any(), any(), any());

        // 2 successes, 1 failure tagged by tenant
        assertThat(counterValue("fabt.audit.anchor.upload.count", "result", "success")).isEqualTo(2.0);
        Counter failureCounter = meterRegistry.find("fabt.audit.anchor.upload.count")
                .tag("result", "failure")
                .tag("tenant_id", TENANT_B.toString())
                .counter();
        assertThat(failureCounter)
                .as("Failure counter must be tagged with the failing tenant_id")
                .isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);

        // tenants_anchored reports successes only
        assertThat(counterValue("fabt.audit.anchor.tenants_anchored.count", null, null)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("empty chain head table — tasklet completes cleanly with no uploads")
    void emptyChainHeadTable() throws Exception {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        Tasklet tasklet = config.anchorTenantsTasklet();
        tasklet.execute(null, null);

        verify(anchorService, never()).uploadAnchor(any(), any(), any(), any(), any());
        assertThat(counterValue("fabt.audit.anchor.upload.count", "result", "success")).isEqualTo(0.0);
        assertThat(counterValue("fabt.audit.anchor.tenants_anchored.count", null, null)).isEqualTo(0.0);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> rowMap(UUID tenantId, byte[] lastHash, UUID lastRowId) {
        // HashMap (not Map.of) — Map.of rejects null values, but lastRowId
        // is legitimately null for chain heads where last_row_id has not
        // been bumped past the V80 NULL seed.
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("tenant_id", tenantId);
        row.put("last_hash", lastHash);
        row.put("last_row_id", lastRowId);
        return row;
    }

    private static byte[] nonZeroHash(int seed) {
        byte[] h = new byte[32];
        for (int i = 0; i < 32; i++) h[i] = (byte) ((seed + i) & 0xFF);
        return h;
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var find = meterRegistry.find(name);
        if (tagKey != null && tagValue != null) {
            find = find.tag(tagKey, tagValue);
        }
        Counter c = find.counter();
        return c == null ? 0.0 : c.count();
    }
}
