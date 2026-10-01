package com.agentic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Executable architecture rules (see docs/adr/0001). These fail the build when a change
 * breaks a module boundary, so the design on paper and the code cannot drift apart.
 * Rules are added as modules land; each one states the decision it protects.
 */
@AnalyzeClasses(packages = "com.agentic", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Modules may depend on each other only in one direction; cycles make them inseparable. */
    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.agentic.(*)..").should().beFreeOfCycles();

    /** platform is the shared foundation; it must not know about business modules. */
    @ArchTest
    static final ArchRule platformDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("com.agentic.platform..")
            .should().dependOnClassesThat().resideInAnyPackage("com.agentic.shortener..", "com.agentic.orchestration..");

    /** Time must come from the injected Clock so expiry/timeouts/metrics are testable. */
    @ArchTest
    static final ArchRule timeComesFromInjectedClock = noClasses()
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .because("time must come from the injected java.time.Clock bean");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
