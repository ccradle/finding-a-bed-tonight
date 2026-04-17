package org.fabt.shared.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.security.CrossTenantJwtException;
import org.fabt.shared.security.RevokedJwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the JWT-side exception handlers added in A4.1 — parallel
 * to {@link GlobalExceptionHandlerCrossTenantTest} (which covers the A3
 * ciphertext-side mapping).
 *
 * <p>Verifies the warroom contracts:
 * <ul>
 *   <li>D25 + W1 — {@code CrossTenantJwtException} → 403 + audit with
 *       enriched JSONB shape</li>
 *   <li>D26 — {@code RevokedJwtException} → 401, NO audit (would flood
 *       post-rotation), counter only</li>
 * </ul>
 */
@DisplayName("GlobalExceptionHandler — JWT exceptions (A4 D25 + D26)")
class GlobalExceptionHandlerJwtTest {

    private static final UUID KID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID EXPECTED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTUAL_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLAIMS_SUB = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final long CLAIMS_IAT = 1_700_000_000L;
    private static final long CLAIMS_EXP = 1_700_000_900L;

    // ------------------------------------------------------------------
    // CrossTenantJwtException → 403 + enriched audit event (D25 + W1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CrossTenantJwtException → 403 with D3 cross_tenant envelope")
    void crossTenantJwt_returns403() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        ResponseEntity<ErrorResponse> response = handler.handleCrossTenantJwt(
                new CrossTenantJwtException(KID, EXPECTED_TENANT, ACTUAL_TENANT,
                        CLAIMS_SUB, CLAIMS_IAT, CLAIMS_EXP));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("cross_tenant", response.getBody().error());
        assertEquals(403, response.getBody().status());
    }

    @Test
    @DisplayName("CrossTenantJwtException publishes audit with action CROSS_TENANT_JWT_REJECTED")
    void crossTenantJwt_publishesAuditAction() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        handler.handleCrossTenantJwt(new CrossTenantJwtException(
                KID, EXPECTED_TENANT, ACTUAL_TENANT,
                CLAIMS_SUB, CLAIMS_IAT, CLAIMS_EXP));

        Object event = captured.get();
        assertNotNull(event, "publisher must have received an event");
        assertTrue(event instanceof AuditEventRecord);
        AuditEventRecord record = (AuditEventRecord) event;
        assertEquals("CROSS_TENANT_JWT_REJECTED", record.action());
        assertNull(record.targetUserId(), "no targetUserId — target is a token, not a user");
    }

    @Test
    @DisplayName("CrossTenantJwtException audit details carry W1 enriched JSONB shape")
    void crossTenantJwt_auditDetailsEnriched() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        handler.handleCrossTenantJwt(new CrossTenantJwtException(
                KID, EXPECTED_TENANT, ACTUAL_TENANT,
                CLAIMS_SUB, CLAIMS_IAT, CLAIMS_EXP));

        AuditEventRecord record = (AuditEventRecord) captured.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) record.details();

        // A3-parity baseline fields
        assertEquals(KID.toString(), details.get("kid"));
        assertEquals(EXPECTED_TENANT.toString(), details.get("expectedTenantId"));
        assertEquals(ACTUAL_TENANT.toString(), details.get("actualTenantId"));
        assertEquals("null", details.get("actorUserId"));
        assertEquals("null", details.get("sourceIp"));

        // W1 — enriched body-claim fields for forensic reconstruction
        assertEquals(EXPECTED_TENANT.toString(), details.get("claimsTenantId"));
        assertEquals(CLAIMS_SUB.toString(), details.get("claimsSub"));
        assertEquals(CLAIMS_IAT, details.get("claimsIat"));
        assertEquals(CLAIMS_EXP, details.get("claimsExp"));
    }

    @Test
    @DisplayName("CrossTenantJwtException counter increments with expected_tenant tag")
    void crossTenantJwt_counterIncrements() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), meters, _e -> {});

        handler.handleCrossTenantJwt(new CrossTenantJwtException(
                KID, EXPECTED_TENANT, ACTUAL_TENANT,
                CLAIMS_SUB, CLAIMS_IAT, CLAIMS_EXP));

        double count = meters.find("fabt.security.cross_tenant_jwt_rejected.count")
                .tag("expected_tenant", EXPECTED_TENANT.toString())
                .counter().count();
        assertEquals(1.0, count);
    }

    @Test
    @DisplayName("CrossTenantJwtException tolerates null body-claim fields (legacy claims missing)")
    void crossTenantJwt_handlesNullClaims() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        handler.handleCrossTenantJwt(new CrossTenantJwtException(
                KID, EXPECTED_TENANT, ACTUAL_TENANT, null, null, null));

        AuditEventRecord record = (AuditEventRecord) captured.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) record.details();
        assertEquals("null", details.get("claimsSub"));
        assertEquals("null", details.get("claimsIat"));
        assertEquals("null", details.get("claimsExp"));
    }

    @Test
    @DisplayName("C-A4-1 — unknown-kid path (null expectedTenantId) → handler does NOT NPE; tag = 'unknown'")
    void crossTenantJwt_unknownKidPath_doesNotNpe() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), meters, captured::set);

        // Unknown-kid path constructs the exception per the C-A3-1 sentinel
        // pattern: kid + null expectedTenantId + sentinel actualTenantId +
        // null body claims (the JWT body was never parsed).
        UUID sentinel = UUID.fromString("00000000-0000-0000-0000-000000000000");
        ResponseEntity<ErrorResponse> response = handler.handleCrossTenantJwt(
                new CrossTenantJwtException(KID, null, sentinel, null, null, null));

        // Pre-fix: this would NPE on .toString() of the null expected tenant.
        // Post-fix: 403 returned cleanly + counter tagged "unknown" + audit
        // shape preserved with "unknown" string for null fields.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("cross_tenant", response.getBody().error());

        // Counter tag uses literal "unknown" (not crash on null)
        double count = meters.find("fabt.security.cross_tenant_jwt_rejected.count")
                .tag("expected_tenant", "unknown")
                .counter().count();
        assertEquals(1.0, count);

        // Audit JSONB carries "unknown" so dashboards can group by tag without filtering null
        AuditEventRecord record = (AuditEventRecord) captured.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) record.details();
        assertEquals("unknown", details.get("expectedTenantId"));
        assertEquals("unknown", details.get("claimsTenantId"));
        assertEquals(sentinel.toString(), details.get("actualTenantId"));
    }

    // ------------------------------------------------------------------
    // RevokedJwtException → 401, no audit (D26)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RevokedJwtException → 401 with token_revoked envelope")
    void revokedJwt_returns401() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        ResponseEntity<ErrorResponse> response = handler.handleRevokedJwt(
                new RevokedJwtException(KID));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("token_revoked", response.getBody().error());
        assertEquals(401, response.getBody().status());
    }

    @Test
    @DisplayName("RevokedJwtException does NOT publish an audit event (would flood post-rotation)")
    void revokedJwt_doesNotPublishAudit() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), new SimpleMeterRegistry(), captured::set);

        handler.handleRevokedJwt(new RevokedJwtException(KID));

        assertNull(captured.get(),
                "RevokedJwtException must NOT publish an audit event (D26 anti-flood);"
                + " the counter is the only signal");
    }

    @Test
    @DisplayName("RevokedJwtException counter increments")
    void revokedJwt_counterIncrements() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), meters, _e -> {});

        handler.handleRevokedJwt(new RevokedJwtException(KID));

        double count = meters.find("fabt.security.revoked_jwt_validate.count")
                .counter().count();
        assertEquals(1.0, count);
    }
}
