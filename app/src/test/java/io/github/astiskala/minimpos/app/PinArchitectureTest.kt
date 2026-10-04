package io.github.astiskala.minimpos.app

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.astiskala.minimpos.app.data.security.PinManager
import org.junit.Assert.assertTrue
import org.junit.Test

class PinArchitectureTest {
    @Test
    fun `only PIN entry and security code verify PINs`() {
        val app =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("io.github.astiskala.minimpos.app")
        ownership.check(app)
    }

    @Test
    fun `PIN ownership rejects verification outside PIN entry`() {
        val imported = ClassFileImporter().importClasses(PinViolation::class.java)
        assertTrue(ownership.description, ownership.evaluate(imported).hasViolation())
    }

    private class PinViolation {
        suspend fun verify(manager: PinManager) = manager.verify("1234")
    }

    private companion object {
        val verifiesPin =
            object : DescribedPredicate<JavaMethodCall>("verify a PIN") {
                override fun test(call: JavaMethodCall): Boolean =
                    call.target.owner.name == PinManager::class.java.name && call.target.name.substringBefore('$') == "verify"
            }

        // PIN entry owns verification and lockout; other features request approval without handling a verifier.
        val ownership: ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackages(
                    "io.github.astiskala.minimpos.app.feature.lock..",
                    "io.github.astiskala.minimpos.app.data.security..",
                ).should()
                .callMethodWhere(verifiesPin)
    }
}
