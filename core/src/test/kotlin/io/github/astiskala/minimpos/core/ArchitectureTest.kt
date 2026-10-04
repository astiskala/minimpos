package io.github.astiskala.minimpos.core

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.Test

/** :core is the platform-independent heart of the app: money, tax, cart, receipts and the QR code formats. */
class ArchitectureTest {
    @Test
    fun `core depends only on the JDK, Kotlin and kotlinx serialization`() =
        classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "io.github.astiskala.minimpos.core..",
                "java..",
                "kotlin..",
                "kotlinx.serialization..",
                "org.jetbrains.annotations..",
            ).check(core)

    @Test
    fun `money is never held in floating-point numbers`() {
        noFields().should().haveRawType(floatingPoint).check(core)
        noMethods().should().haveRawReturnType(floatingPoint).check(core)
        noCodeUnits()
            .should()
            .haveRawParameterTypes(DescribedPredicate.describe("a floating-point parameter") { it.any(floatingPoint::test) })
            .check(core)
    }

    @Test
    fun `packages have no dependency cycles`() =
        slices()
            .matching("io.github.astiskala.minimpos.core.(*)..")
            .should()
            .beFreeOfCycles()
            .check(core)

    @Test
    fun `core never logs or prints`() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(core)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(core)
    }

    @Test
    fun `core throws specific exceptions`() = GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(core)

    private companion object {
        val core: JavaClasses =
            ClassFileImporter()
                .withImportOption(
                    ImportOption.Predefined.DO_NOT_INCLUDE_TESTS,
                ).importPackages("io.github.astiskala.minimpos.core")

        val floatingPoint: DescribedPredicate<JavaClass> =
            nameMatching("double|float|java\\.lang\\.Double|java\\.lang\\.Float").forSubtype()
    }
}
