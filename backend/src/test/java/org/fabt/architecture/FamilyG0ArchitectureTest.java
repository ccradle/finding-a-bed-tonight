package org.fabt.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.fabt.shared.audit.AuditEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit Family G-0 — test-sentinel guardrail. Introduced by
 * multi-tenant-production-readiness task 8.0.15, warroom-deferred from Slice
 * G-0 (issue #98).
 *
 * <h2>Why this rule exists</h2>
 *
 * <p>{@link AuditEventType#TEST_PROBE} is a dedicated sentinel enum case
 * reserved for audit-infrastructure tests that need to publish a synthetic
 * {@link org.fabt.shared.audit.AuditEventRecord} without polluting a
 * business case's counter tag or audit-trail meaning. See
 * {@code AuditEventType.TEST_PROBE} Javadoc for the full rationale.</p>
 *
 * <p>Because the sentinel lives in the production enum (pragmatic — avoids
 * a String-escape-hatch on {@code AuditEventRecord.action}), a developer
 * could paste {@code AuditEventType.TEST_PROBE} into a production code path
 * and ship it. This rule makes that compile-time-invisible path
 * test-time-visible: any production reference fails ArchUnit and blocks
 * the build.</p>
 *
 * <h2>Scope</h2>
 *
 * <p>All classes under {@code backend/src/main/java/} (ArchUnit's main
 * classpath import). Test-side references ({@code backend/src/test/java/})
 * are the intended use and are outside this rule's scope — we import main
 * classes only.</p>
 *
 * <p>The {@code AuditEventType} enum itself is exempt (it declares the case).</p>
 */
@DisplayName("ArchUnit Family G-0 — TEST_PROBE sentinel guardrail")
class FamilyG0ArchitectureTest {

    @Test
    @DisplayName("Production code must not reference AuditEventType.TEST_PROBE")
    void productionCodeMustNotReferenceTestProbe() {
        ArchRule rule = noClasses()
                .should(referenceTestProbe())
                .allowEmptyShould(false)
                .as("Family G-0: no production reference to AuditEventType.TEST_PROBE")
                .because("TEST_PROBE is a test-only sentinel. See AuditEventType.TEST_PROBE Javadoc. "
                        + "A production reference would pollute audit-trail semantics + "
                        + "Prometheus counter tags, and operators filter 'action != TEST_PROBE' "
                        + "in dashboards on the assumption this case never appears in prod.");

        // Import main classes only — excludes test sources where TEST_PROBE is
        // intentionally referenced.
        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt"));
    }

    /**
     * Fires a violation for every class (other than {@link AuditEventType}
     * itself) that reads the {@link AuditEventType#TEST_PROBE} enum constant.
     * Enum-constant access compiles to a GETSTATIC on the declaring class's
     * synthetic field, which ArchUnit surfaces as a field access.
     */
    private static ArchCondition<JavaClass> referenceTestProbe() {
        String enumClassName = AuditEventType.class.getName();
        String sentinelFieldName = AuditEventType.TEST_PROBE.name();

        return new ArchCondition<>("reference AuditEventType." + sentinelFieldName) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                // Self-reference exemption: the enum declaration itself has
                // a synthetic accessor for every case.
                if (javaClass.getName().equals(enumClassName)) {
                    return;
                }

                javaClass.getFieldAccessesFromSelf().forEach(access -> {
                    if (access.getTarget().getOwner().getName().equals(enumClassName)
                            && access.getTarget().getName().equals(sentinelFieldName)) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                String.format("%s references AuditEventType.%s at %s — "
                                        + "TEST_PROBE is test-only; use a real business case",
                                        javaClass.getName(),
                                        sentinelFieldName,
                                        access.getSourceCodeLocation())));
                    }
                });
            }
        };
    }
}
