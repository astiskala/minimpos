package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.Test

class ArchitectureTest {
    @Test
    fun `repository tooling has no Android app or payment dependencies`() = dependencyRule.check(tooling)

    @Test
    fun `dependency boundary rejects a deliberate test-library violation`() {
        assertThat(dependencyRule.evaluate(ClassFileImporter().importClasses(LibraryViolation::class.java)).hasViolation()).isTrue()
    }

    private companion object {
        val tooling: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.astiskala.minimpos.tooling")

        // Signing must run offline without Android, Gradle plugins or additional runtime libraries.
        val dependencyRule: ArchRule =
            classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                    "io.github.astiskala.minimpos.tooling..",
                    "java..",
                    "javax.xml..",
                    "org.w3c.dom..",
                    "org.xml.sax..",
                    "kotlin..",
                    "kotlinx.serialization..",
                    "org.jetbrains.annotations..",
                )
    }
}

private class LibraryViolation {
    fun testLibrary() = Test::class.java
}
