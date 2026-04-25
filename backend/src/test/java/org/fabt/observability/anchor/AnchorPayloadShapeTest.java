package org.fabt.observability.anchor;

import java.time.Instant;
import java.util.UUID;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditChainAnchorService} — pinned anchor payload shape
 * and OCI client interaction. No Spring context, no real OCI calls.
 *
 * <p><b>Why pin the payload shape</b>: every anchor uploaded to OCI is
 * immutable for 7 years (locked retention rule). A future schema change
 * means the bucket holds payloads in two formats — forensic readers must
 * handle both. The {@code anchor_format_version} field is the dispatch key.
 * Changing the v1 shape after first ship is a migration event.
 */
@DisplayName("AuditChainAnchorService — payload shape + OCI interaction contract")
class AnchorPayloadShapeTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROW    = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN    = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant T   = Instant.parse("2026-04-27T05:00:00Z");

    private static OciAuditAnchorProperties props() {
        return new OciAuditAnchorProperties(
                true,
                "us-ashburn-1",
                "ocid1.tenancy.oc1..test",
                "ocid1.user.oc1..test",
                "aa:bb:cc",
                "/dev/null",
                "test-namespace",
                "test-bucket",
                "ocid1.compartment.oc1..test",
                null);
    }

    private static byte[] sampleHash() {
        byte[] h = new byte[32];
        for (int i = 0; i < 32; i++) h[i] = (byte) i;
        return h;
    }

    @Test
    @DisplayName("v1 payload has exactly the 6 fields in the contracted order")
    void v1PayloadShapePinned() {
        AuditChainAnchorService svc = new AuditChainAnchorService(mock(ObjectStorage.class), props());
        String payload = svc.buildAnchorPayload(TENANT, sampleHash(), ROW, T, RUN);

        // Exact bit-for-bit pin. If this assertion ever fails, you are
        // about to make a change that requires a migration of every
        // historical anchor in the bucket.
        String expected = "{"
                + "\"anchor_format_version\":\"v1\","
                + "\"tenant_id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"last_hash_hex\":\"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\","
                + "\"last_row_id\":\"22222222-2222-2222-2222-222222222222\","
                + "\"anchored_at\":\"2026-04-27T05:00:00Z\","
                + "\"run_id\":\"33333333-3333-3333-3333-333333333333\""
                + "}";
        assertThat(payload).isEqualTo(expected);
    }

    @Test
    @DisplayName("null last_row_id renders as JSON null, not empty string")
    void nullLastRowIdRenderedAsJsonNull() {
        AuditChainAnchorService svc = new AuditChainAnchorService(mock(ObjectStorage.class), props());
        String payload = svc.buildAnchorPayload(TENANT, sampleHash(), null, T, RUN);
        assertThat(payload).contains("\"last_row_id\":null");
        assertThat(payload).doesNotContain("\"last_row_id\":\"\"");
    }

    @Test
    @DisplayName("invalid hash length rejected with clear message")
    void invalidHashLengthRejected() {
        AuditChainAnchorService svc = new AuditChainAnchorService(mock(ObjectStorage.class), props());
        assertThatThrownBy(() -> svc.buildAnchorPayload(TENANT, new byte[31], ROW, T, RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> svc.buildAnchorPayload(TENANT, null, ROW, T, RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("object key follows audit-anchors/yyyy/MM/dd/{tenant}-{run}.json shape")
    void objectKeyShapePinned() {
        AuditChainAnchorService svc = new AuditChainAnchorService(mock(ObjectStorage.class), props());
        String key = svc.buildObjectKey(TENANT, T, RUN);
        assertThat(key).isEqualTo(
                "audit-anchors/2026/04/27/11111111-1111-1111-1111-111111111111-33333333-3333-3333-3333-333333333333.json");
    }

    @Test
    @DisplayName("uploadAnchor calls PutObject exactly once with the contracted request shape — no DELETE attempt")
    void uploadCallsPutObjectOnly() {
        ObjectStorage client = mock(ObjectStorage.class);
        PutObjectResponse resp = PutObjectResponse.builder().eTag("test-etag").build();
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(resp);

        AuditChainAnchorService svc = new AuditChainAnchorService(client, props());
        AuditChainAnchorService.AnchorUploadResult result =
                svc.uploadAnchor(TENANT, sampleHash(), ROW, T, RUN);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, times(1)).putObject(captor.capture());

        PutObjectRequest req = captor.getValue();
        assertThat(req.getNamespaceName()).isEqualTo("test-namespace");
        assertThat(req.getBucketName()).isEqualTo("test-bucket");
        assertThat(req.getObjectName()).isEqualTo(
                "audit-anchors/2026/04/27/11111111-1111-1111-1111-111111111111-33333333-3333-3333-3333-333333333333.json");
        assertThat(req.getContentType()).isEqualTo("application/json");

        assertThat(result.objectKey()).isEqualTo(req.getObjectName());
        assertThat(result.etag()).isEqualTo("test-etag");
        assertThat(result.payloadSha256())
                .as("SHA-256 hex of the uploaded payload bytes — 64 hex chars")
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("delete + overwrite paths are NEVER invoked from uploadAnchor")
    void noDeleteOrOverwritePaths() {
        // Belt-and-braces: even if a future refactor adds a delete branch,
        // this test catches it. The IAM policy on the tenancy already
        // refuses these operations, but defense in depth.
        ObjectStorage client = mock(ObjectStorage.class);
        PutObjectResponse resp = PutObjectResponse.builder().eTag("test-etag").build();
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(resp);

        AuditChainAnchorService svc = new AuditChainAnchorService(client, props());
        svc.uploadAnchor(TENANT, sampleHash(), ROW, T, RUN);

        verify(client, never()).deleteObject(any());
        // No "rename" / "overwrite" semantic in the SDK; PutObject would
        // overwrite by default — but the IAM policy blocks
        // OBJECT_OVERWRITE. Our happy-path uses a unique object key
        // (tenant + run UUID), so overwrite never happens by construction.
    }
}
