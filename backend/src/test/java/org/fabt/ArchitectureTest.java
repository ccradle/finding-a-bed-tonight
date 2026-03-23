package org.fabt;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "org.fabt", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // --- Shared kernel must not depend on any domain module ---

    // shared.security is allowed to depend on auth module (Design D1 rule #5:
    // "auth module is a dependency exception — security filters need auth services")
    @ArchTest
    static final ArchRule shared_non_security_should_not_depend_on_modules =
            noClasses().that().resideInAPackage("org.fabt.shared..")
                    .and().resideOutsideOfPackage("org.fabt.shared.security..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant..",
                            "org.fabt.auth..",
                            "org.fabt.shelter..",
                            "org.fabt.dataimport..",
                            "org.fabt.observability..",
                            "org.fabt.subscription..",
                            "org.fabt.availability..",
                            "org.fabt.reservation..",
                            "org.fabt.surge.."
                    ).as("Shared kernel (except security) must not depend on any domain module");

    @ArchTest
    static final ArchRule shared_security_only_depends_on_auth =
            noClasses().that().resideInAPackage("org.fabt.shared.security..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant..",
                            "org.fabt.shelter..",
                            "org.fabt.dataimport..",
                            "org.fabt.observability..",
                            "org.fabt.subscription..",
                            "org.fabt.availability..",
                            "org.fabt.reservation..",
                            "org.fabt.surge.."
                    ).as("Shared security may depend on auth module but not other modules");

    // --- Modules must not access other modules' repositories ---

    @ArchTest
    static final ArchRule tenant_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.tenant..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.repository..",
                            "org.fabt.shelter.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Tenant module must not access other modules' repositories");

    @ArchTest
    static final ArchRule auth_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.auth..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.shelter.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Auth module must not access other modules' repositories");

    @ArchTest
    static final ArchRule shelter_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.shelter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Shelter module must not access other modules' repositories");

    @ArchTest
    static final ArchRule dataimport_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.dataimport..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Data import module must not access tenant, auth, subscription, availability, reservation, or surge repositories");

    @ArchTest
    static final ArchRule subscription_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.subscription..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.shelter.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Subscription module must not access other modules' repositories");

    @ArchTest
    static final ArchRule availability_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.availability..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Availability module must not access tenant, auth, dataimport, subscription, reservation, or surge repositories");

    @ArchTest
    static final ArchRule reservation_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.reservation..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Reservation module must not access tenant, auth, dataimport, subscription, or surge repositories");

    @ArchTest
    static final ArchRule surge_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.surge..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.reservation.repository.."
                    ).as("Surge module must not access tenant, auth, dataimport, subscription, or reservation repositories");

    @ArchTest
    static final ArchRule referral_should_not_access_other_repositories =
            noClasses().that().resideInAPackage("org.fabt.referral..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.tenant.repository..",
                            "org.fabt.auth.repository..",
                            "org.fabt.dataimport.repository..",
                            "org.fabt.subscription.repository..",
                            "org.fabt.availability.repository..",
                            "org.fabt.reservation.repository..",
                            "org.fabt.surge.repository.."
                    ).as("Referral module must not access other modules' repositories (service access allowed)");

    // --- No module should directly access another module's domain entities ---

    @ArchTest
    static final ArchRule modules_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.shelter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.subscription.domain..",
                            "org.fabt.availability.domain..",
                            "org.fabt.reservation.domain..",
                            "org.fabt.surge.domain.."
                    ).as("Shelter module must not access auth, dataimport, subscription, availability, reservation, or surge domain entities");

    @ArchTest
    static final ArchRule availability_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.availability..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.subscription.domain..",
                            "org.fabt.tenant.domain..",
                            "org.fabt.reservation.domain..",
                            "org.fabt.surge.domain.."
                    ).as("Availability module must not access auth, dataimport, subscription, tenant, reservation, or surge domain entities");

    @ArchTest
    static final ArchRule subscription_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.subscription..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.shelter.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.tenant.domain..",
                            "org.fabt.availability.domain..",
                            "org.fabt.reservation.domain..",
                            "org.fabt.surge.domain.."
                    ).as("Subscription module must not access other modules' domain entities");

    @ArchTest
    static final ArchRule reservation_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.reservation..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.subscription.domain..",
                            "org.fabt.surge.domain.."
                    ).as("Reservation module must not access auth, dataimport, subscription, or surge domain entities");

    @ArchTest
    static final ArchRule surge_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.surge..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.subscription.domain..",
                            "org.fabt.reservation.domain.."
                    ).as("Surge module must not access auth, dataimport, subscription, or reservation domain entities");

    @ArchTest
    static final ArchRule referral_should_not_access_other_domain_entities =
            noClasses().that().resideInAPackage("org.fabt.referral..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.fabt.auth.domain..",
                            "org.fabt.dataimport.domain..",
                            "org.fabt.subscription.domain..",
                            "org.fabt.availability.domain..",
                            "org.fabt.reservation.domain..",
                            "org.fabt.surge.domain.."
                    ).as("Referral module must not access other modules' domain entities");

    // --- API controllers must reside in api packages ---

    @ArchTest
    static final ArchRule controllers_should_be_in_api_packages =
            classes().that().haveSimpleNameEndingWith("Controller")
                    .should().resideInAPackage("..api..")
                    .allowEmptyShould(true)
                    .as("Controllers must reside in api packages");

    // --- Repositories must reside in repository packages ---

    @ArchTest
    static final ArchRule repositories_should_be_in_repository_packages =
            classes().that().haveSimpleNameEndingWith("Repository")
                    .should().resideInAPackage("..repository..")
                    .allowEmptyShould(true)
                    .as("Repositories must reside in repository packages");
}
