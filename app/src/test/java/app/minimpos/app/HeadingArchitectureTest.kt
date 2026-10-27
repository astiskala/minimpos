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

class HeadingArchitectureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `screens mark headings only through the shared heading components`() = sharedHeadings.check(ArchitectureTest.app)

    @Test
    fun `a screen marking its own heading is rejected`() {
        assertTrue(sharedHeadings.evaluate(fixture("app/minimpos/app/feature/HeadingFixture")).hasViolation())
    }

    @Test
    fun `the shared components may mark headings`() {
        assertFalse(sharedHeadings.evaluate(fixture("app/minimpos/app/ui/components/HeadingFixture")).hasViolation())
    }

    /** Synthetic bytecode that calls `heading()` from [name]'s package, as a screen's semantics lambda would. */
    private fun fixture(name: String): JavaClasses {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "mark", "($RECEIVER)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, SEMANTICS, "heading", "($RECEIVER)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        val file = temporary.newFolder().resolve("HeadingFixture.class")
        file.writeBytes(writer.toByteArray())
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        const val SEMANTICS = "androidx/compose/ui/semantics/SemanticsPropertiesKt"
        const val RECEIVER = "Landroidx/compose/ui/semantics/SemanticsPropertyReceiver;"

        // Headings look and read the same everywhere only while SectionHeader, Subheading, SheetTitle, StepHeader and
        // OutcomeHeader are their one source; a screen styling and marking its own drifts from them.
        val sharedHeadings: ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackage("app.minimpos.app.ui.components..")
                .should()
                .callMethod(
                    "androidx.compose.ui.semantics.SemanticsPropertiesKt",
                    "heading",
                    "androidx.compose.ui.semantics.SemanticsPropertyReceiver",
                ).because("screens use the shared heading components, which alone mark headings for screen readers")
                .allowEmptyShould(true)
    }
}
