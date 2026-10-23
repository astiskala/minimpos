package app.minimpos.app

import androidx.room.Query
import app.minimpos.app.data.db.SaleEntity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerformanceArchitectureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `every receipt QR and transfer preparation pipeline retains a CPU boundary`() {
        cpuFamilies.forEach { assertWithMessage(it).that(offloads(ArchitectureTest.app, it)).isTrue() }
    }

    @Test
    fun `CPU guard rejects a preparation producer without dispatch`() {
        val prefix = "app.minimpos.app.email.ReceiptEmailer"
        assertThat(offloads(fixture(prefix), prefix)).isFalse()
    }

    @Test
    fun `history maintenance and lookup SQL stays lightweight and bounded`() = historySql.check(ArchitectureTest.app)

    @Test
    fun `SQL guard rejects full payload reads and unbounded batches`() {
        assertThat(historySql.evaluate(ClassFileImporter().importClasses(UnsafeHistory::class.java)).hasViolation()).isTrue()
    }

    private interface UnsafeHistory {
        @Query("SELECT * FROM sales ORDER BY createdAt")
        fun historyBatch(): List<SaleEntity>
    }

    @Test
    fun `ordinary sale events never load line and tender relations`() = eventReads.check(ArchitectureTest.app)

    @Test
    fun `event read guard rejects loading a full sale from a mutation`() {
        val prefix = "app.minimpos.app.data.repo.SaleRepository\$record\$Bad"
        assertThat(eventReads.evaluate(fixture(prefix, "app/minimpos/app/data/db/SaleDao", "sale")).hasViolation()).isTrue()
    }

    @Test
    fun `transfer animation retains lifecycle suspension`() {
        assertThat(
            calls(
                ArchitectureTest.app,
                "app.minimpos.app.feature.transfer.TransferScreensKt",
                "androidx.lifecycle.RepeatOnLifecycleKt",
                "repeatOnLifecycle",
            ),
        ).isTrue()
    }

    @Test
    fun `lifecycle guard rejects a playback owner without lifecycle suspension`() {
        val prefix = "app.minimpos.app.feature.transfer.TransferScreensKt"
        assertThat(calls(fixture(prefix), prefix, "androidx.lifecycle.RepeatOnLifecycleKt", "repeatOnLifecycle")).isFalse()
    }

    private fun fixture(
        name: String,
        owner: String = "java/lang/Object",
        method: String = "toString",
    ): JavaClasses {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, "()Ljava/lang/Object;", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        val file = temporary.newFolder().resolve("Fixture.class").apply { writeBytes(writer.toByteArray()) }
        return ClassFileImporter().importPaths(file.toPath())
    }

    private companion object {
        val cpuFamilies =
            listOf(
                "app.minimpos.app.email.ReceiptEmailer",
                "app.minimpos.app.feature.transfer.TransferImportViewModel",
                "app.minimpos.app.feature.transfer.TransferExportViewModel",
                "app.minimpos.app.ui.components.WidgetsKt\$QrImage",
                "app.minimpos.app.ui.components.WidgetsKt\$ReceiptPreview",
                "app.minimpos.app.share.ShareSheetKt\$ShareEffect",
                "app.minimpos.app.payment.ReceiptDelivery",
            )

        fun calls(
            classes: JavaClasses,
            prefix: String,
            owner: String,
            method: String,
        ): Boolean =
            classes
                .filter { it.name == prefix || it.name.startsWith(prefix + "$") }
                .flatMap { it.methodCallsFromSelf }
                .any { it.target.owner.name == owner && it.target.name == method }

        fun offloads(
            classes: JavaClasses,
            prefix: String,
        ): Boolean =
            calls(classes, prefix, "kotlinx.coroutines.BuildersKt", "withContext") ||
                calls(classes, prefix, "kotlinx.coroutines.flow.FlowKt", "flowOn")

        // Large receipt/encryption blobs never belong to list/maintenance reads; batch limits must stay in SQL.
        val historySql: ArchRule =
            methods()
                .that()
                .areAnnotatedWith(Query::class.java)
                .and()
                .haveNameMatching("historyBatch|retentionBatch|historyParents|historyRecords|shopperSales|sampleSales")
                .should(
                    object : ArchCondition<JavaMethod>("omit payloads and bound history reads") {
                        override fun check(
                            item: JavaMethod,
                            events: ConditionEvents,
                        ) {
                            val sql =
                                item
                                    .getAnnotationOfType(Query::class.java)
                                    .value
                                    .lowercase()
                                    .replace(Regex("\\s+"), " ")
                            val batch = item.name in setOf("historyBatch", "retentionBatch", "shopperSales")
                            val lightweight = !sql.contains("select *") && sql.contains("null as customerreceiptjson")
                            events.add(SimpleConditionEvent(item, lightweight && (!batch || sql.contains("limit :limit")), item.fullName))
                        }
                    },
                )

        // SaleEvent owns transitions; recording one must not hydrate unrelated line/tender relations.
        val eventReads: ArchRule =
            noClasses().should().callMethodWhere(
                object : DescribedPredicate<JavaMethodCall>("load sale relations for an event") {
                    override fun test(call: JavaMethodCall): Boolean {
                        val source = call.origin.owner.name
                        val event =
                            (source == "app.minimpos.app.data.repo.SaleRepository" && call.origin.name == "record") ||
                                source.startsWith("app.minimpos.app.data.repo.SaleRepository\$record\$")
                        return event && call.target.owner.name == "app.minimpos.app.data.db.SaleDao" && call.target.name == "sale"
                    }
                },
            )
    }
}
