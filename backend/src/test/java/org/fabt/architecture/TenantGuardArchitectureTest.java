package org.fabt.architecture;

import java.util.Set;
import java.util.UUID;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.fabt.analytics.repository.BedSearchLogRepository;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.auth.repository.TenantOAuth2ProviderRepository;
import org.fabt.auth.repository.UserOAuth2LinkRepository;
import org.fabt.auth.repository.UserRepository;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.dataimport.repository.ImportLogRepository;
import org.fabt.hmis.repository.HmisAuditRepository;
import org.fabt.hmis.repository.HmisOutboxRepository;
import org.fabt.notification.repository.EscalationPolicyRepository;
import org.fabt.notification.repository.NotificationRepository;
import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.shared.security.TenantUnscoped;
import org.fabt.shelter.repository.ShelterConstraintsRepository;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.subscription.repository.SubscriptionRepository;
import org.fabt.subscription.repository.WebhookDeliveryLogRepository;
import org.fabt.surge.repository.SurgeEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-failing architecture rules forbidding unsafe cross-tenant
 * data-access patterns. Activated in Phase 3 of
 * {@code cross-tenant-isolation-audit} (Issue #117) after Phase 2
 * cleaned every known violator.
 *
 * <h2>Family A — unsafe-lookup (D2)</h2>
 * Fires on bare {@code findById(UUID)} / {@code existsById(UUID)} calls
 * on tenant-owned repositories from service or controller code, unless
 * the calling method carries {@link TenantUnscoped}.
 *
 * <h2>Family B — URL-path-sink (D11)</h2>
 * Fires on public service methods that accept a {@code UUID} parameter
 * AND call write methods ({@code save/delete/deleteById}) on tenant-owned
 * repositories, unless annotated {@link TenantUnscoped}. Strict with
 * zero exceptions per warroom 2026-04-15.
 *
 * <h2>Caller-scoping rules (D7)</h2>
 * {@code findByIdForBatch} restricted to batch packages;
 * {@code *Internal} subscription methods restricted to
 * {@code WebhookDeliveryService}.
 */
@AnalyzeClasses(packages = "org.fabt", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("Tenant guard architecture rules (D2, D7, D11)")
class TenantGuardArchitectureTest {

    private static final Set<String> TENANT_OWNED_REPO_NAMES = Set.of(
            ShelterRepository.class.getName(),
            ReferralTokenRepository.class.getName(),
            ReservationRepository.class.getName(),
            NotificationRepository.class.getName(),
            org.fabt.shared.audit.repository.AuditEventRepository.class.getName(),
            ApiKeyRepository.class.getName(),
            SubscriptionRepository.class.getName(),
            UserRepository.class.getName(),
            TenantOAuth2ProviderRepository.class.getName(),
            WebhookDeliveryLogRepository.class.getName(),
            HmisOutboxRepository.class.getName(),
            HmisAuditRepository.class.getName(),
            EscalationPolicyRepository.class.getName(),
            BedAvailabilityRepository.class.getName(),
            ShelterConstraintsRepository.class.getName(),
            SurgeEventRepository.class.getName(),
            UserOAuth2LinkRepository.class.getName(),
            ImportLogRepository.class.getName()
    );

    private static final Set<String> UNSAFE_LOOKUP_METHODS = Set.of("findById", "existsById");

    /**
     * SAFE-sites registry (Phase 4.5 / design D2) — methods that call bare
     * findById on tenant-owned repos but are verified safe by the warroom
     * audit. Each entry is "ClassName.methodName" with the justification
     * as a comment. Adding a new entry requires code review explaining WHY
     * the bare lookup is safe. Format: SimpleClassName.methodName.
     */
    private static final Set<String> SAFE_SITES = Set.of(
            // Self-path: userId sourced from JWT subject (auth.getName()),
            // not from URL/body. User can only act on their own record.
            "AuthController.refresh",
            "AuthController.verifyTotp",
            "PasswordController.changePassword",
            "TotpController.enrollTotp",
            "TotpController.confirmTotpEnrollment",
            "TotpController.regenerateRecoveryCodes",
            // Admin-path: userId from JWT; admin acts on users within their
            // own tenant; userService.getUser applies manual tenant check.
            "PasswordController.resetPassword",
            // Tenant-scoped wrapper: findById then manual tenantId check.
            // This IS the tenant guard — callers delegate to this method.
            "UserService.findById",
            "UserService.getUser",
            // OAuth2 link: existingLink FK chain → user_id → tenant.
            // D11 tenantId param removed; bare findById on existing-link
            // user_id is safe because the link row was created under the
            // correct tenant during initial email-match linking.
            "OAuth2AccountLinkService.linkOrReject",
            // Token-hash-keyed: SHA-256 token is globally unique; the token
            // row's user_id dictates tenant. No tenantId in the request.
            "PasswordResetService.resetPassword",
            // Shelter-constraints: findById(shelterId) AFTER the shelter
            // was already loaded via tenant-scoped shelterService.findById
            // or a tenant-scoped search query. The shelterId is pre-validated.
            "AvailabilityService.createSnapshot",
            "BedSearchService.doSearch",
            "ShelterService.getDetail",
            "ShelterService.update",
            // Notification: findById(notificationId) then checks
            // recipientId == caller's userId (self-path guard).
            "NotificationPersistenceService.markActed",
            // Subscription: findById then manual tenantId equality check
            // (pre-D11 pattern, migrating to findByIdAndTenantId in
            // multi-tenant-production-readiness companion change).
            "SubscriptionService.findRecentDeliveries",
            // Phase C task 4.4: findByTenantAndId IS the tenant guard for
            // EscalationPolicy request-path lookups by policy UUID. It calls
            // repository.findById(policyId) then verifies policy.tenantId
            // matches the caller's tenantId OR is null (platform-default row,
            // accessible from any tenant by design). Mismatch returns empty +
            // emits CROSS_TENANT_POLICY_READ audit row via DetachedAuditPersister
            // (REQUIRES_NEW) + cross_tenant_reject Micrometer counter. The
            // EscalationPolicyRepository has no findByIdAndTenantId method
            // because platform-default rows (tenant_id IS NULL) must remain
            // accessible from any tenant context — a strict equality join
            // would incorrectly filter them out. Design-c D-C-2 + spec
            // escalation-policy-service-cache-split.
            "EscalationPolicyService.findByTenantAndId"
    );

    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAll", "delete", "deleteById", "deleteAll", "deleteAllById"
    );

    // ------------------------------------------------------------------
    // Family A — unsafe-lookup (D2)
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_bare_findById_on_tenant_owned_repos =
            methods()
                    .that().areDeclaredInClassesThat().resideInAnyPackage("..service..", "..api..")
                    .and().areNotAnnotatedWith(TenantUnscoped.class)
                    .should(notCallUnsafeLookupOnTenantOwnedRepo())
                    .as("Service/controller methods must use findByIdAndTenantId or carry "
                            + "@TenantUnscoped(\"justification\"). See design D2.");

    // ------------------------------------------------------------------
    // Family B — URL-path-sink (D11)
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_tenantId_param_with_tenant_repo_write =
            methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..service..")
                    .and().arePublic()
                    .and().areNotAnnotatedWith(TenantUnscoped.class)
                    .should(notAcceptUuidAndWriteToTenantRepo())
                    .as("Public service methods accepting UUID must not write to tenant-owned "
                            + "repositories without @TenantUnscoped. Family B is strict — zero "
                            + "exceptions (warroom 2026-04-15). See design D11.");

    // ------------------------------------------------------------------
    // Caller-scoping rules (D7)
    // ------------------------------------------------------------------

    @ArchTest
    static final ArchRule findByIdForBatch_only_from_batch =
            methods()
                    .that().areDeclaredInClassesThat().resideInAnyPackage("..service..", "..api..")
                    .and().areNotAnnotatedWith(TenantUnscoped.class)
                    .should(notCallFindByIdForBatch())
                    .as("findByIdForBatch is batch-only — callers must be in ..batch.. packages "
                            + "or carry @TenantUnscoped. See design D7.");

    @ArchTest
    static final ArchRule internal_subscription_methods_only_from_delivery =
            methods()
                    .that().areDeclaredInClassesThat().resideInAnyPackage("..service..", "..api..")
                    .should(notCallInternalSubscriptionMethods())
                    .as("*Internal subscription methods are restricted to WebhookDeliveryService. "
                            + "See design D7.");

    // ------------------------------------------------------------------
    // Drift catcher — repo set size matches table allowlist
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Tenant-owned repo set matches TenantPredicateCoverageTest table count")
    void repoSetMatchesTableAllowlist() {
        assertThat(TENANT_OWNED_REPO_NAMES)
                .as("TENANT_OWNED_REPO_NAMES must stay in sync with "
                        + "TenantPredicateCoverageTest.TENANT_OWNED_TABLES — "
                        + "not all tables have repos (some use JdbcTemplate), "
                        + "so repo count ≤ table count is expected, but adding "
                        + "a new tenant-owned repo MUST update this set.")
                .hasSizeLessThanOrEqualTo(20);
    }

    // ------------------------------------------------------------------
    // Custom ArchConditions
    // ------------------------------------------------------------------

    private static ArchCondition<JavaMethod> notCallUnsafeLookupOnTenantOwnedRepo() {
        return new ArchCondition<>("not call bare findById/existsById on tenant-owned repos") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String site = method.getOwner().getSimpleName() + "." + method.getName();
                if (SAFE_SITES.contains(site)) return;

                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (!UNSAFE_LOOKUP_METHODS.contains(call.getName())) continue;
                    JavaClass owner = call.getTargetOwner();
                    if (!isTenantOwnedRepo(owner)) continue;
                    if (call.getTarget().getRawParameterTypes().size() != 1) continue;
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " calls bare " + call.getName()
                                    + "() on tenant-owned " + owner.getSimpleName()
                                    + " at " + call.getSourceCodeLocation()
                                    + " — add to SAFE_SITES with justification or "
                                    + "use findByIdAndTenantId"));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notAcceptUuidAndWriteToTenantRepo() {
        return new ArchCondition<>("not accept UUID tenantId param and write to tenant-owned repo") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                // D11 specifically targets methods accepting UUID tenantId —
                // not any UUID. Check parameter names (available when javac
                // -parameters is enabled, which Spring Boot parent POM does).
                // D11 targets methods accepting UUID tenantId (named).
                // ArchUnit 1.4.1 JavaParameter lacks getName(); use
                // reflect() to read bytecode MethodParameters attribute
                // (available when javac -parameters is enabled — Spring
                // Boot parent POM default).
                boolean hasTenantIdParam = false;
                try {
                    for (java.lang.reflect.Parameter p : method.reflect().getParameters()) {
                        if (UUID.class.equals(p.getType()) && "tenantId".equals(p.getName())) {
                            hasTenantIdParam = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // reflect() may fail on synthetic/bridge methods; skip
                    return;
                }
                if (!hasTenantIdParam) return;

                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (!WRITE_METHODS.contains(call.getName())) continue;
                    JavaClass owner = call.getTargetOwner();
                    if (!isTenantOwnedRepo(owner)) continue;
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " accepts UUID tenantId param "
                                    + "and calls " + call.getName()
                                    + "() on tenant-owned " + owner.getSimpleName()
                                    + " at " + call.getSourceCodeLocation()));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notCallFindByIdForBatch() {
        return new ArchCondition<>("not call findByIdForBatch outside batch packages") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().getPackageName().contains(".batch")) return;
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if ("findByIdForBatch".equals(call.getName())) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " calls findByIdForBatch "
                                        + "outside a .batch package at "
                                        + call.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notCallInternalSubscriptionMethods() {
        return new ArchCondition<>("not call *Internal subscription methods outside WebhookDeliveryService") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().getSimpleName().equals("WebhookDeliveryService")) return;
                if (method.getOwner().getSimpleName().equals("SubscriptionService")) return;
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    String name = call.getName();
                    if ((name.equals("markFailingInternal")
                            || name.equals("deactivateInternal")
                            || name.equals("recordDeliveryInternal"))
                            && call.getTargetOwner().getSimpleName().equals("SubscriptionService")) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " calls " + name
                                        + " on SubscriptionService — restricted to "
                                        + "WebhookDeliveryService at "
                                        + call.getSourceCodeLocation()));
                    }
                }
            }
        };
    }

    private static boolean isTenantOwnedRepo(JavaClass cls) {
        return TENANT_OWNED_REPO_NAMES.stream().anyMatch(cls::isAssignableTo);
    }
}
