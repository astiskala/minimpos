package app.minimpos.app

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
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

class ThemeArchitectureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `colour literals and corner shapes stay in the theme`() = themeValues.check(ArchitectureTest.app)

    @Test
    fun `a feature building its own colour or corner shape is rejected`() {
        listOf(
            Call(COLOR_KT, "Color", "(J)J"),
            Call(COLOR_KT, "Color-xETnrds", "(FFFFLandroidx/compose/ui/graphics/colorspace/ColorSpace;)J"),
            Call("$SHAPE/RoundedCornerShapeKt", "RoundedCornerShape-0680j_4", "(F)L$SHAPE/RoundedCornerShape;"),
            Call("$SHAPE/CutCornerShapeKt", "CutCornerShape", "(I)L$SHAPE/CutCornerShape;"),
            Call("$SHAPE/RoundedCornerShape", "<init>", "(L$SHAPE/CornerSize;L$SHAPE/CornerSize;L$SHAPE/CornerSize;L$SHAPE/CornerSize;)V"),
        ).forEach { call ->
            assertTrue(call.toString(), themeValues.evaluate(fixture(FEATURE, call)).hasViolation())
        }
    }

    @Test
    fun `the theme defines them and features may use shared shapes and convert colours`() {
        assertFalse(themeValues.evaluate(fixture(THEME, Call(COLOR_KT, "Color", "(J)J"))).hasViolation())
        listOf(
            Call(COLOR_KT, "toArgb-8_81llA", "(J)I"),
            Call("$SHAPE/RoundedCornerShapeKt", "getCircleShape", "()L$SHAPE/RoundedCornerShape;"),
        ).forEach { call ->
            assertFalse(call.toString(), themeValues.evaluate(fixture(FEATURE, call)).hasViolation())
        }
    }

    /** A call from a synthetic class to the static [owner].[method] (or constructor `<init>`) with [descriptor]. */
    private data class Call(
        val owner: String,
        val method: String,
        val descriptor: String,
    )

    /** A synthetic class named [name] whose one method makes [call]; ArchUnit reads the call without verifying it. */
    private fun fixture(
        name: String,
        call: Call,
    ): JavaClasses {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "use", "()V", null, null)
        method.visitCode()
        val opcode = if (call.method == "<init>") Opcodes.INVOKESPECIAL else Opcodes.INVOKESTATIC
        method.visitMethodInsn(opcode, call.owner, call.method, call.descriptor, false)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        val file = temporary.newFolder().resolve("${name.substringAfterLast('/')}.class")
        file.writeBytes(writer.toByteArray())
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        const val FEATURE = "app/minimpos/app/feature/ThemeFixture"
        const val THEME = "app/minimpos/app/ui/theme/ThemeFixture"
        const val COLOR_KT = "androidx/compose/ui/graphics/ColorKt"
        const val SHAPE = "androidx/compose/foundation/shape"

        val shapeFactories = Regex("""androidx\.compose\.foundation\.shape\.(Absolute)?(RoundedCorner|CutCorner)Shape(Kt)?""")

        // DESIGN.md's colours and corner radii live in ui.theme; values built elsewhere drift from the spec and its
        // contrast tests. Color(…) compiles to ColorKt.Color or a mangled Color-…; the shape factories to their *Kt
        // functions or constructors. ColorKt's conversions and the shared CircleShape and RectangleShape stay allowed.
        val themeValues: ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackage("app.minimpos.app.ui.theme..")
                .should()
                .callCodeUnitWhere(
                    DescribedPredicate.describe<JavaCall<*>>("a Color(…) or corner-shape factory") { call ->
                        val owner = call.targetOwner.name
                        val name = call.name
                        when {
                            owner == "androidx.compose.ui.graphics.ColorKt" -> name == "Color" || name.startsWith("Color-")
                            !shapeFactories.matches(owner) -> false
                            owner.endsWith("Kt") -> name.contains("CornerShape")
                            else -> name == "<init>"
                        }
                    },
                ).because("feature code reads colours and shapes from MaterialTheme and LocalStatusColors (DESIGN.md)")
                // A theme-only fixture leaves nothing to check; the app always has classes outside the theme.
                .allowEmptyShould(true)
    }
}
