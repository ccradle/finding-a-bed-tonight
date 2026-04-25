package org.fabt.observability.anchor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Phase G-3 — Builds the canonical anchor payload for one tenant + uploads
 * it to OCI Object Storage. Stateless; one method per tenant per run.
 *
 * <h2>Anchor payload shape (load-bearing — do not change without coordinated
 * verifier update)</h2>
 *
 * <pre>{@code
 * {
 *   "anchor_format_version": "v1",
 *   "tenant_id": "...",
 *   "last_hash_hex": "...",
 *   "last_row_id": "...",
 *   "anchored_at": "2026-04-25T05:00:00Z",
 *   "run_id": "..."
 * }
 * }</pre>
 *
 * <p>Future schema changes increment {@code anchor_format_version} so a
 * forensic reader can dispatch on the version. The first published payload
 * pins this shape forever — operators reading historical anchors must be
 * able to reconstruct every previous version.
 *
 * <h2>Object key shape</h2>
 *
 * <p>{@code audit-anchors/yyyy/MM/dd/{tenant_id}-{run_id}.json}.
 *
 * <p>Date prefix simplifies forensic retrieval ("show me all anchors from a
 * specific week"). One run produces one object per tenant — no overwrite
 * concerns even if versioning is suspended (each run id is unique).
 *
 * <h2>Security posture</h2>
 *
 * <ul>
 *   <li>Uses ONLY {@code PutObject}. The IAM policy on the tenancy explicitly
 *       omits {@code OBJECT_DELETE} and {@code OBJECT_OVERWRITE}; even if
 *       this code attempted them, the bucket would refuse.</li>
 *   <li>Bucket has a 7-year locked retention rule (Oracle-enforced WORM).</li>
 *   <li>This service never reads the OCI private key directly. The SDK
 *       reads it via {@code SimpleAuthenticationDetailsProvider}.</li>
 * </ul>
 *
 * <p>Bean is conditional on {@code fabt.oci.audit-anchor.enabled=true} —
 * matches the {@link OciAuditAnchorConfig} gating so the service and its
 * dependencies are co-existent.
 */
@Service
@ConditionalOnProperty(prefix = "fabt.oci.audit-anchor", name = "enabled", havingValue = "true")
public class AuditChainAnchorService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainAnchorService.class);
    public static final String ANCHOR_FORMAT_VERSION = "v1";

    private final ObjectStorage objectStorage;
    private final OciAuditAnchorProperties props;

    public AuditChainAnchorService(ObjectStorage objectStorage, OciAuditAnchorProperties props) {
        this.objectStorage = objectStorage;
        this.props = props;
    }

    /**
     * Build the canonical JSON payload for a single tenant's anchor row.
     * Deterministic field order; SHA-256 of the byte representation can be
     * cross-verified post-upload for tamper-detection on the OCI side.
     *
     * @param tenantId    the tenant whose chain head is being anchored
     * @param lastHash    32-byte chain-head hash (rendered hex-lowercase)
     * @param lastRowId   the {@code audit_events.id} of the row this hash
     *                    corresponds to (may be null only for tenants whose
     *                    chain has just been seeded — pre-V85 case is filtered
     *                    by the caller)
     * @param anchoredAt  the wall-clock time the anchor row was assembled
     * @param runId       a UUID identifying the verifier run this anchor
     *                    belongs to (allows correlating anchors back to a
     *                    specific job execution in {@code BATCH_JOB_EXECUTION})
     */
    public String buildAnchorPayload(UUID tenantId, byte[] lastHash, UUID lastRowId,
                                      Instant anchoredAt, UUID runId) {
        if (lastHash == null || lastHash.length != 32) {
            throw new IllegalArgumentException(
                    "lastHash must be exactly 32 bytes (SHA-256 digest); got "
                    + (lastHash == null ? "null" : lastHash.length + " bytes"));
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"anchor_format_version\":\"").append(ANCHOR_FORMAT_VERSION).append("\",");
        sb.append("\"tenant_id\":\"").append(tenantId).append("\",");
        sb.append("\"last_hash_hex\":\"").append(HexFormat.of().formatHex(lastHash)).append("\",");
        sb.append("\"last_row_id\":");
        if (lastRowId == null) {
            sb.append("null");
        } else {
            sb.append('"').append(lastRowId).append('"');
        }
        sb.append(',');
        sb.append("\"anchored_at\":\"").append(anchoredAt.toString()).append("\",");
        sb.append("\"run_id\":\"").append(runId).append('"');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Build the object-storage key for a given anchor row.
     */
    public String buildObjectKey(UUID tenantId, Instant anchoredAt, UUID runId) {
        DateTimeFormatter datePath = DateTimeFormatter.ofPattern("yyyy/MM/dd")
                .withZone(java.time.ZoneOffset.UTC);
        return "audit-anchors/" + datePath.format(anchoredAt)
                + "/" + tenantId + "-" + runId + ".json";
    }

    /**
     * Upload a single tenant's anchor row to OCI Object Storage.
     *
     * @return SHA-256 hex of the uploaded payload bytes — useful for
     *         operator verification (cross-reference with the
     *         {@code Content-MD5}/{@code etag} returned by OCI for additional
     *         transit-integrity confidence).
     * @throws RuntimeException if the upload fails. The caller (batch
     *         tasklet) catches per-tenant and continues; the failure is
     *         metric-logged so Prometheus can alert on sustained failure.
     */
    public AnchorUploadResult uploadAnchor(UUID tenantId, byte[] lastHash, UUID lastRowId,
                                            Instant anchoredAt, UUID runId) {
        String payload = buildAnchorPayload(tenantId, lastHash, lastRowId, anchoredAt, runId);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String objectKey = buildObjectKey(tenantId, anchoredAt, runId);
        String payloadSha256 = sha256Hex(payloadBytes);

        PutObjectRequest req = PutObjectRequest.builder()
                .namespaceName(props.namespace())
                .bucketName(props.bucket())
                .objectName(objectKey)
                .contentType("application/json")
                .contentLength((long) payloadBytes.length)
                .putObjectBody(new ByteArrayInputStream(payloadBytes))
                .build();

        PutObjectResponse resp = objectStorage.putObject(req);
        log.info("OCI audit-anchor uploaded: tenant={} key={} size={}B etag={} payloadSha256={}",
                tenantId, objectKey, payloadBytes.length, resp.getETag(), payloadSha256);
        return new AnchorUploadResult(objectKey, resp.getETag(), payloadSha256);
    }

    private static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Returned by {@link #uploadAnchor}. */
    public record AnchorUploadResult(String objectKey, String etag, String payloadSha256) {}
}
