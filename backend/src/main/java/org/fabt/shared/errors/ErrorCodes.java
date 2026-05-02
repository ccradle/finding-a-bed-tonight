package org.fabt.shared.errors;

/**
 * Registry of structured error codes for client-parseable error responses.
 *
 * <p>The convention: each constant is a dotted path identifying the error
 * class. The string flows through {@link StructuredErrorException} into the
 * 400-response {@code context.errorCode} field, where the frontend can match
 * on it programmatically (e.g., to surface a specific UX for a known
 * rejection vs. a generic "bad request").
 *
 * <p>Adding a new code: declare a constant here, then throw
 * {@code new StructuredErrorException(ErrorCodes.YOUR_CODE, "human-readable message")}.
 * The {@link org.fabt.shared.web.GlobalExceptionHandler} surfaces the code
 * in the response without further wiring.
 */
public final class ErrorCodes {

    /**
     * The tenant DV-policy flag is currently {@code true} and the operator
     * is attempting to flip to {@code false} while at least one active
     * shelter on the tenant has {@code dv_shelter = true}. The operator
     * must first deactivate (or migrate) every active DV shelter before
     * disabling the flag.
     *
     * <p>The error message accompanying this code MUST include the count
     * of remaining active DV shelters so the operator can scope the work
     * needed to reach the disable-eligible state. The message MUST NOT
     * include shelter names, addresses, or identifiers — count only.
     */
    public static final String TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST =
            "tenant.dvPolicy.cannotDisableWhileDvSheltersExist";

    /**
     * A shelter create / update / activate request set {@code dv_shelter = true}
     * while the parent tenant's {@code dv_policy_enabled} flag is
     * {@code false} (or absent). The operator must first enable DV shelter
     * operations at the tenant level via the admin Settings panel before
     * any shelter on the tenant can be flagged as a DV shelter.
     */
    public static final String SHELTER_DV_SHELTER_REQUIRES_DV_POLICY =
            "shelter.dvShelter.requiresDvPolicy";

    /**
     * A COC_ADMIN attempted to invoke a tenant-scoped admin endpoint
     * targeting a tenant other than the one bound to their JWT. The 403
     * response carries no body (existence-leak prevention); the structured
     * code surfaces only in audit rows for forensic visibility.
     */
    public static final String TENANT_CROSS_TENANT_ACCESS = "tenant.crossTenantAccess";

    /**
     * Platform-level observability config write rejected because a monitor
     * interval was outside the [1, 1440] minute range (D4 bounds), an unknown
     * field key was supplied, or a non-numeric value was supplied for an
     * interval field. New in platform-observability-split (2026-05-02).
     *
     * <p>Bounds rationale: 1-minute floor prevents NOAA-API rate-limit floods;
     * 1440-minute ceiling (24h) prevents operator-typo'd intervals that would
     * silently disable monitoring.
     */
    public static final String PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE =
            "platform.observability.intervalOutOfRange";

    /**
     * Platform-level {@code tracing_endpoint} write rejected because the
     * supplied value is not a valid URI (missing scheme, missing host, blank,
     * or non-string type). New in platform-observability-split (2026-05-02).
     */
    public static final String PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED =
            "platform.observability.tracingEndpointMalformed";

    private ErrorCodes() {
        // utility class
    }
}
