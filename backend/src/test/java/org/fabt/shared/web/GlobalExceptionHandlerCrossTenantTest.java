package org.fabt.shared.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.security.CrossTenantCiphertextException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link GlobalExceptionHandler#handleCrossTenantCiphertext}
 * — verifies the contract from A3 D21 + warroom Q5 + C-A3-2:
 *
 * <ol>
 *   <li>Returns 403 with the D3 envelope {@code {"error":"cross_tenant",...}}</li>
 *   <li>Publishes an {@link AuditEventRecord} with action
 *       {@code CROSS_TENANT_CIPHERTEXT_REJECTED}</li>
 *   <li>Audit details JSONB carries
 *       {@code {kid, expectedTenantId, actualTenantId, actorUserId, sourceIp}}</li>
 * </ol>
 *
 * <p>Pure unit test — no Spring context. {@link ApplicationEventPublisher}
 * is captured as a lambda; we don't need {@code AuditEventService} to
 * actually persist anything to verify the contract here. End-to-end DB
 * persistence is covered by the AuditEventService's own tests + by
 * existing audit_events ITs that use {@code AuditEventRecord}.
 */
@DisplayName("GlobalExceptionHandler — CrossTenantCiphertextException contract (Q5 + C-A3-2)")
class GlobalExceptionHandlerCrossTenantTest {

    private static final UUID KID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID EXPECTED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTUAL_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("returns 403 with D3 cross_tenant envelope")
    void returns403WithD3Envelope() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(),
                new SimpleMeterRegistry(),
                captured::set);

        ResponseEntity<ErrorResponse> response = handler.handleCrossTenantCiphertext(
                new CrossTenantCiphertextException(KID, EXPECTED_TENANT, ACTUAL_TENANT));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("cross_tenant", response.getBody().error());
        assertEquals(403, response.getBody().status());
    }

    @Test
    @DisplayName("publishes AuditEventRecord with action CROSS_TENANT_CIPHERTEXT_REJECTED")
    void publishesAuditEventWithCorrectAction() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(),
                new SimpleMeterRegistry(),
                captured::set);

        handler.handleCrossTenantCiphertext(
                new CrossTenantCiphertextException(KID, EXPECTED_TENANT, ACTUAL_TENANT));

        Object event = captured.get();
        assertNotNull(event, "ApplicationEventPublisher must have received an event");
        assertTrue(event instanceof AuditEventRecord, "event must be an AuditEventRecord");
        AuditEventRecord record = (AuditEventRecord) event;
        assertEquals("CROSS_TENANT_CIPHERTEXT_REJECTED", record.action());
    }

    @Test
    @DisplayName("audit details carry the Q5 JSONB shape: kid + expected + actual + actor + sourceIp")
    void auditDetailsCarryQ5JsonbShape() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(),
                new SimpleMeterRegistry(),
                captured::set);

        handler.handleCrossTenantCiphertext(
                new CrossTenantCiphertextException(KID, EXPECTED_TENANT, ACTUAL_TENANT));

        AuditEventRecord record = (AuditEventRecord) captured.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) record.details();

        assertEquals(KID.toString(), details.get("kid"));
        assertEquals(EXPECTED_TENANT.toString(), details.get("expectedTenantId"));
        assertEquals(ACTUAL_TENANT.toString(), details.get("actualTenantId"));
        // actorUserId + sourceIp are "null" string when no request context is bound
        // (this is a unit test — no servlet request available).
        assertEquals("null", details.get("actorUserId"));
        assertEquals("null", details.get("sourceIp"));
    }

    @Test
    @DisplayName("targetUserId is null because target is a ciphertext, not a user")
    void targetUserIdIsNull() {
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(),
                new SimpleMeterRegistry(),
                captured::set);

        handler.handleCrossTenantCiphertext(
                new CrossTenantCiphertextException(KID, EXPECTED_TENANT, ACTUAL_TENANT));

        AuditEventRecord record = (AuditEventRecord) captured.get();
        assertEquals(null, record.targetUserId());
    }

    @Test
    @DisplayName("counter increments with expected_tenant tag")
    void counterIncrementsWithExpectedTenantTag() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicReference<Object> captured = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new StaticMessageSource(), meters, captured::set);

        handler.handleCrossTenantCiphertext(
                new CrossTenantCiphertextException(KID, EXPECTED_TENANT, ACTUAL_TENANT));

        double count = meters.find("fabt.security.cross_tenant_ciphertext_rejected.count")
                .tag("expected_tenant", EXPECTED_TENANT.toString())
                .counter()
                .count();
        assertEquals(1.0, count);
    }
}
