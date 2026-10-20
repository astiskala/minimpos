package app.minimpos.app

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
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

class MaterialArchitectureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `all app components use Material 3 instead of Material 2`() = material3.check(ArchitectureTest.app)

    @Test
    fun `Material 2 components and themes are rejected`() {
        listOf(
            "androidx/compose/material/ButtonKt",
            "androidx/compose/material/TextKt",
            "androidx/compose/material/MaterialTheme",
        ).forEach { dependency ->
            assertTrue(dependency, material3.evaluate(fixture(dependency)).hasViolation())
        }
    }

    @Test
    fun `Material 3 and shared icons and ripple remain allowed`() {
        listOf(
            "androidx/compose/material3/ButtonKt",
            "androidx/compose/material3/MaterialTheme",
            "androidx/compose/material3/adaptive/layout/ThreePaneScaffoldRole",
            "androidx/compose/material/icons/Icons",
            "androidx/compose/material/ripple/RippleAlpha",
        ).forEach { dependency ->
            assertFalse(dependency, material3.evaluate(fixture(dependency)).hasViolation())
        }
    }

    /** Synthetic bytecode tests forbidden dependencies without adding Material 2 to the app or test classpath. */
    private fun fixture(dependency: String): JavaClasses {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/minimpos/fixture/MaterialFixture", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "component", "L$dependency;", null, null).visitEnd()
        writer.visitEnd()
        val file = temporary.newFolder().resolve("MaterialFixture.class")
        file.writeBytes(writer.toByteArray())
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        // Mixing Material generations bypasses our theme; icons and ripple are shared infrastructure, not M2 widgets.
        val material3: ArchRule =
            noClasses()
                .should()
                .dependOnClassesThat(
                    resideInAnyPackage("androidx.compose.material..").and(
                        not(resideInAnyPackage("androidx.compose.material.icons..", "androidx.compose.material.ripple..")),
                    ),
                ).because("all app components and themes use Material 3")
    }
}
