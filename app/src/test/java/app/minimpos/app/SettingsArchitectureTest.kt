package app.minimpos.app

import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.terminal.DestinationRules
import app.minimpos.terminal.transport.CloudRegion
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassVisitor
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Label
import com.tngtech.archunit.thirdparty.org.objectweb.asm.MethodVisitor
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale

class SettingsArchitectureTest {
    @Test
    fun `connection fields change only through terminal settings or learned destination rules`() =
        ownership.check(
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { !it.contains("UnitTest") }
                .importPackages("app.minimpos.app"),
        )

    @Test
    fun `connection ownership rejects each direct copy and constructor bypass`() {
        listOf(
            DestinationViolation::class.java,
            EnvironmentViolation::class.java,
            RegionViolation::class.java,
            ConstructorViolation::class.java,
        ).forEach { violation ->
            assertTrue(violation.simpleName, ownership.evaluate(ClassFileImporter().importClasses(violation)).hasViolation())
        }
    }

    @Test
    fun `connection ownership permits ordinary settings copies and defaults`() =
        ownership.check(ClassFileImporter().importClasses(AllowedCopies::class.java))

    @Test
    fun `connection copy masks match the current model`() {
        val fields = TerminalSettings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertThat(fields.take(3).map { it.name }).containsExactly("mode", "environment", "cloudRegion").inOrder()
        assertThat(fields.size).isLessThan(Int.SIZE_BITS)
    }

    @Test
    fun `an unverifiable copy mask cannot inherit an earlier literal`() {
        val violations = mutableListOf<String>()
        val visitor = ConnectionWrites { _, message -> violations += message }
        visitor.visitIntInsn(Opcodes.BIPUSH, 7)
        visitor.visitFieldInsn(Opcodes.GETSTATIC, "Fixture", "mask", "I")
        visitor.visitInsn(Opcodes.ACONST_NULL)
        visitor.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            TerminalSettings::class.java.name.replace('.', '/'),
            "copy\$default",
            "()V",
            false,
        )
        assertThat(violations).hasSize(1)
    }

    @Test
    fun `app-language overrides cannot change the process-wide locale`() =
        localeIsolation.check(
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { !it.contains("UnitTest") }
                .importPackages("app.minimpos.app"),
        )

    @Test
    fun `locale isolation rejects a global override`() {
        assertTrue(localeIsolation.evaluate(ClassFileImporter().importClasses(LocaleMutationViolation::class.java)).hasViolation())
    }

    private class LocaleMutationViolation {
        fun change() = Locale.setDefault(Locale.JAPAN)
    }

    private class DestinationViolation {
        fun change(settings: TerminalSettings) = settings.copy(mode = TerminalMode.CLOUD)
    }

    private class EnvironmentViolation {
        fun change(settings: TerminalSettings) = settings.copy(environment = TerminalEnvironment.LIVE)
    }

    private class RegionViolation {
        fun change(settings: TerminalSettings) = settings.copy(cloudRegion = CloudRegion.AU)
    }

    private class ConstructorViolation {
        fun make() = TerminalSettings(mode = TerminalMode.CLOUD)
    }

    private class AllowedCopies {
        fun rename(settings: TerminalSettings) = settings.copy(merchantAccount = "Merchant", host = "terminal")

        fun defaults() = TerminalSettings()
    }

    private companion object {
        val localeIsolation: ArchRule =
            noClasses()
                .should()
                .callMethodWhere(
                    DescribedPredicate.describe<JavaMethodCall>("change the process-wide locale") {
                        it.targetOwner.name == Locale::class.java.name && it.target.name == "setDefault"
                    },
                ).because("app-language overrides must not change device-country pricing or unrelated Android services")

        val ownership: ArchRule =
            classes()
                .should(
                    object : ArchCondition<JavaClass>("keep connection field writes behind their owned interface") {
                        override fun check(
                            item: JavaClass,
                            events: ConditionEvents,
                        ) {
                            if (item.name == TerminalSettings::class.java.name ||
                                item.name.startsWith("${TerminalSettings::class.java.name}\$")
                            ) {
                                return
                            }
                            val resource = "/${item.name.replace('.', '/')}.class"
                            val stream = checkNotNull(SettingsArchitectureTest::class.java.getResourceAsStream(resource)) { resource }
                            stream.use {
                                ClassReader(it).accept(
                                    object : ClassVisitor(Opcodes.ASM9) {
                                        override fun visitMethod(
                                            access: Int,
                                            name: String,
                                            descriptor: String,
                                            signature: String?,
                                            exceptions: Array<out String>?,
                                        ): MethodVisitor =
                                            ConnectionWrites { fields, violation ->
                                                val learnedRule =
                                                    item.isAssignableTo(DestinationRules::class.java) && name == "learnedEnvironment" &&
                                                        fields != null && fields and 1 == 0
                                                if (!learnedRule) {
                                                    events.add(SimpleConditionEvent.violated(item, "${item.name}.$name: $violation"))
                                                }
                                            }
                                    },
                                    ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                                )
                            }
                        }
                    },
                ).because(
                    "destination and environment changes must preserve coupled fields instead of reconstructing their rules in callers",
                )
    }
}

private class ConnectionWrites(
    private val violation: (Int?, String) -> Unit,
) : MethodVisitor(Opcodes.ASM9) {
    private var constant: Int? = null

    override fun visitInsn(opcode: Int) {
        constant =
            when (opcode) {
                in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> opcode - Opcodes.ICONST_0
                Opcodes.ACONST_NULL -> constant
                else -> null
            }
    }

    override fun visitIntInsn(
        opcode: Int,
        operand: Int,
    ) {
        constant = if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) operand else null
    }

    override fun visitLdcInsn(value: Any) {
        constant = value as? Int
    }

    override fun visitVarInsn(
        opcode: Int,
        variable: Int,
    ) {
        constant = null
    }

    override fun visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) {
        constant = null
    }

    override fun visitTypeInsn(
        opcode: Int,
        type: String,
    ) {
        constant = null
    }

    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        constant = null
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        if (owner == TerminalSettings::class.java.name.replace('.', '/')) {
            when {
                name == "copy\$default" || (
                    name == "<init>" &&
                        descriptor.endsWith(
                            "Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
                        )
                ) -> {
                    if (constant == null || checkNotNull(constant) and CONNECTION_FIELDS != CONNECTION_FIELDS) {
                        violation(
                            constant?.inv()?.and(CONNECTION_FIELDS),
                            "$name explicitly writes connection fields or has an unverifiable default mask",
                        )
                    }
                }

                name == "copy" || (name == "<init>" && descriptor != "()V") -> {
                    violation(CONNECTION_FIELDS, "$name supplies all connection fields directly")
                }
            }
        }
        constant = null
    }

    private companion object {
        const val CONNECTION_FIELDS = 0b111
    }
}
