package io.github.astiskala.minimpos.app

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Test

class SdkBoundaryArchitectureTest {
    private val app =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption { location -> !location.contains("UnitTest") }
            .importPackages("io.github.astiskala.minimpos.app")

    @Test
    fun `secrets are encrypted in one place`() =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.github.astiskala.minimpos.app.data.security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("javax.crypto..", "android.security.keystore..")
            .check(app)

    @Test
    fun `the Adyen library stays behind adyen`() =
        // adyen's interface has its own print model and enums, so nexo knowledge lives in one module.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.adyen..")
            .check(app)
}
