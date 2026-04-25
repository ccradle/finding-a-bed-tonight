package org.fabt.observability.anchor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase G-3 — Configuration for the OCI Object Storage external audit-chain
 * anchor. Bound from {@code fabt.oci.audit-anchor.*} properties (typically
 * sourced from {@code FABT_OCI_*} environment variables on prod).
 *
 * <p>Default {@link #enabled()} is {@code false} — dev, CI, and local builds
 * skip OCI integration entirely. Production overrides via env var.
 *
 * <p><b>No defaults for OCIDs / keys / endpoints</b> — these MUST be set via
 * env vars on the machine that runs the verifier. Hardcoded production values
 * in source would (1) leak tenancy identity into the public repo and (2) tie
 * the code to a specific OCI tenancy. Operators substitute their own values
 * per the runbook.
 *
 * <p><b>Private key path is a filesystem reference, never the key itself.</b>
 * The OCI Java SDK reads the file via {@code SimpleAuthenticationDetailsProvider}
 * from the configured path. This module never reads the key contents
 * directly.
 */
@ConfigurationProperties(prefix = "fabt.oci.audit-anchor")
public record OciAuditAnchorProperties(
        boolean enabled,
        String region,
        String tenancyOcid,
        String userOcid,
        String fingerprint,
        String privateKeyPath,
        String namespace,
        String bucket,
        String compartmentOcid,
        /**
         * Spring Batch cron expression for the weekly anchor job. Defaults
         * to Monday 05:00 UTC ({@code 0 0 5 * * MON}) per Phase G design.md
         * — well outside business hours and after the daily verifier (which
         * runs at 04:00 UTC). Override via {@code fabt.oci.audit-anchor.cron}.
         */
        String cron) {

    public OciAuditAnchorProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 5 * * MON";
        }
    }
}
