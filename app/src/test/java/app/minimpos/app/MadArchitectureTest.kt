package app.minimpos.app

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Future

class MadArchitectureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `configuration corruption never replaces stored security or recovery data`() = preservesStorage.check(ArchitectureTest.app)

    @Test
    fun `destructive corruption handling is rejected`() {
        assertTrue(
            preservesStorage
                .evaluate(
                    fixture("BadStorage", "androidx/datastore/core/handlers/ReplaceFileCorruptionHandler", "replace", false),
                ).hasViolation(),
        )
    }

    @Test
    fun `camera futures are read only by the lifecycle owner after completion`() = cameraFutures.check(ArchitectureTest.app)

    @Test
    fun `blocking futures outside the camera owner are rejected`() {
        assertTrue(cameraFutures.evaluate(fixture("BadScanner", "java/util/concurrent/Future", "get", true)).hasViolation())
    }

    @Test
    fun `expensive state producers retain their dispatcher boundaries`() {
        cpuBoundaries.check(ArchitectureTest.app)
        pinBoundary.check(ArchitectureTest.app)
        historyReaderBoundary.check(ArchitectureTest.app)
    }

    @Test
    fun `a history producer without offloading is rejected`() {
        val missing = fixture("app/minimpos/app/feature/history/HistoryViewModel", "java/lang/Object", "toString", false)
        assertTrue(cpuBoundaries.evaluate(missing).hasViolation())
    }

    @Test
    fun `a bounded history reader without CPU dispatch is rejected`() {
        val missing = fixture("app/minimpos/app/data/repo/HistoryRepository\$Reader", "java/lang/Object", "toString", false)
        assertTrue(historyReaderBoundary.evaluate(missing).hasViolation())
    }

    @Test
    fun `PIN derivation without offloading is rejected`() {
        val missing = fixture("app/minimpos/app/data/security/PinManager", "java/lang/Object", "toString", false)
        assertTrue(pinBoundary.evaluate(missing).hasViolation())
    }

    @Test
    fun `navigation scenes and platform transitions retain one supported owner`() {
        navigationOwner.check(ArchitectureTest.app)
        supportedBack.check(ArchitectureTest.app)
    }

    @Test
    fun `a second navigation display and legacy Activity navigation are rejected`() {
        assertTrue(
            navigationOwner.evaluate(fixture("BadNavigation", "androidx/navigation3/ui/NavDisplayKt", "NavDisplay", false)).hasViolation(),
        )
        listOf("onBackPressed", "overridePendingTransition", "overrideActivityTransition").forEach { method ->
            assertTrue(supportedBack.evaluate(fixture("BadActivity", "android/app/Activity", method, false)).hasViolation())
        }
    }

    private fun fixture(
        name: String,
        dependency: String,
        method: String,
        isInterface: Boolean,
    ): JavaClasses {
        val type = if ('/' in name) name else "app/minimpos/fixture/$name"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "dependency", "L$dependency;", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                if (isInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
                dependency,
                method,
                "()Ljava/lang/Object;",
                isInterface,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        val file = temporary.newFolder().resolve("Fixture.class")
        file.writeBytes(writer.toByteArray())
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        // One scene host keeps forward/pop/predictive animations and adaptive panes coordinated.
        val navigationOwner: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching("app\\.minimpos\\.app\\.ui\\.navigation\\.AppNavHostKt.*")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("androidx.navigation3.ui.NavDisplayKt")

        // Native Activity overrides bypass AndroidX Back dispatch or replace the system's predictive animations.
        val supportedBack: ArchRule =
            noClasses().should().callMethodWhere(
                object : DescribedPredicate<JavaMethodCall>("use legacy Activity Back or replace platform transitions") {
                    override fun test(call: JavaMethodCall): Boolean =
                        call.target.owner.isAssignableTo("android.app.Activity") &&
                            call.target.name in setOf("onBackPressed", "overridePendingTransition", "overrideActivityTransition")
                },
            )

        // Credentials, PINs and operation journals must survive corruption; defaults are for missing files only.
        val preservesStorage: ArchRule =
            noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("androidx.datastore.core.handlers.ReplaceFileCorruptionHandler")

        // Future.get is safe only in CameraSession's completion listener; disposal and composables never wait.
        val cameraFutures: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching("app\\.minimpos\\.app\\.scan\\.CameraSession.*")
                .should()
                .callMethodWhere(
                    object : DescribedPredicate<JavaMethodCall>("block on a future") {
                        override fun test(call: JavaMethodCall): Boolean =
                            call.target.name == "get" && call.target.owner.isAssignableTo(Future::class.java)
                    },
                )

        val cpuProducers =
            setOf(
                "app.minimpos.app.feature.history.HistoryViewModel",
                "app.minimpos.app.feature.products.ProductsViewModel",
                "app.minimpos.app.feature.sale.SaleViewModel",
            )

        // Room offloads SQL, not downstream transformations; a boundary before stateIn keeps typing off Main.
        val cpuBoundaries: ArchRule =
            classes()
                .that(
                    object : DescribedPredicate<JavaClass>("produce expensive list state") {
                        override fun test(input: JavaClass): Boolean = input.name in cpuProducers
                    },
                ).should(callBoundary("kotlinx.coroutines.flow.FlowKt", "flowOn"))

        // History aggregation now lives in bounded suspend reads; Room SQL alone does not offload their reducers.
        val historyReaderBoundary: ArchRule =
            classes()
                .that()
                .haveFullyQualifiedName("app.minimpos.app.data.repo.HistoryRepository\$Reader")
                .should(callBoundary("kotlinx.coroutines.BuildersKt", "withContext"))

        // A suspend declaration alone does not move PBKDF2 off Main; behavior tests verify the injected dispatcher.
        val pinBoundary: ArchRule =
            classes()
                .that()
                .haveFullyQualifiedName("app.minimpos.app.data.security.PinManager")
                .should(callBoundary("kotlinx.coroutines.BuildersKt", "withContext"))

        fun callBoundary(
            owner: String,
            method: String,
        ) = object : ArchCondition<JavaClass>("offload using $method") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val offloads = item.methodCallsFromSelf.any { it.target.owner.name == owner && it.target.name == method }
                events.add(SimpleConditionEvent(item, offloads, "${item.name} must retain its $method dispatcher boundary"))
            }
        }
    }
}
