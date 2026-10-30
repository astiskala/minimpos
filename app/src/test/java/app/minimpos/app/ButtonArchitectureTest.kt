package app.minimpos.app

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ButtonArchitectureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `feature buttons use shared frames`() = sharedButtons.check(ArchitectureTest.app)

    @Test
    fun `a feature bypassing the shared button frames is rejected`() {
        assertTrue(sharedButtons.evaluate(fixture("androidx/compose/material3/ButtonKt")).hasViolation())
    }

    @Test
    fun `a feature using shared buttons is accepted`() {
        assertFalse(sharedButtons.evaluate(fixture("app/minimpos/app/ui/components/ButtonsKt")).hasViolation())
    }

    /** A synthetic feature depends on [owner], as a call to its button factory would. */
    private fun fixture(owner: String): JavaClasses {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/minimpos/app/feature/ButtonFixture", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "buttonOwner", "L$owner;", null, null).visitEnd()
        writer.visitEnd()
        val file = temporary.newFolder().resolve("ButtonFixture.class")
        file.writeBytes(writer.toByteArray())
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        // Shared frames own shape, label typography and touch size, including custom row/chip content slots.
        val sharedButtons: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("app.minimpos.app.feature..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("androidx.compose.material3.ButtonKt")
                .because("feature actions use the shared button family so their styling and touch sizing cannot drift")
    }
}
