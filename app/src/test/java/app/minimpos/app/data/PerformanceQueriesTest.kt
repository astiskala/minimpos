package app.minimpos.app.data

import androidx.room.Query
import androidx.room.withTransaction
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.RefundDao
import app.minimpos.app.data.db.SaleDao
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.SaleEvent
import com.google.common.truth.Truth.assertThat
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class PerformanceQueriesTest {
    private fun sale(
        id: String,
        at: Long = 1,
    ) = SaleEntity(
        id,
        createdAt = at,
        currency = "AUD",
        taxMode = "INCLUSIVE",
        netMinor = 100,
        taxMinor = 0,
        totalMinor = 100,
        status = SaleStatus.APPROVED,
        merchantReference = id,
    )

    @Test
    fun `failed sample catalog removal rolls back sales and never releases recovery state`() {
        val env = TestEnvironment()
        try {
            val db = env.container.database
            await { db.saleDao().insert(sale("sample").copy(sample = true)) }
            var notified = false
            val history = HistoryRepository(db, onRemoved = { _, _ -> notified = true })
            assertThrows(IllegalStateException::class.java) {
                await { history.removeSamples { error("catalog write failed") } }
            }
            assertThat(notified).isFalse()
            assertThat(await { db.saleDao().header("sample") }).isNotNull()
        } finally {
            env.close()
        }
    }

    @Test
    fun `both maintenance scans use creation indexes without temporary sorting`() {
        val env = TestEnvironment()
        try {
            val db = env.container.database.openHelper.readableDatabase
            for ((dao, table) in listOf(SaleDao::class.java to "sales", RefundDao::class.java to "refunds")) {
                val method =
                    ClassFileImporter()
                        .importClasses(dao)
                        .single()
                        .methods
                        .single { it.name == "retentionBatch" }
                val query =
                    method
                        .getAnnotationOfType(Query::class.java)
                        .value
                        .replace(":afterAt", Long.MIN_VALUE.toString())
                        .replace(":afterId", "''")
                        .replace(":before", "1000")
                        .replace(":limit", "256")
                db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                    val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                    assertThat(plan).contains("index_${table}_createdAt_id")
                    assertThat(plan).doesNotContain("TEMP B-TREE")
                }
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun `retention skips recent and recently active rows but preserves unknown outcomes`() {
        val env = TestEnvironment()
        try {
            await {
                val db = env.container.database
                db.withTransaction {
                    repeat(600) { db.saleDao().insert(sale("recent-$it", 10_000)) }
                    db.saleDao().insert(sale("old"))
                    db.saleDao().insert(sale("unknown").copy(status = SaleStatus.UNKNOWN))
                    db.saleDao().insert(sale("recent-capture").copy(captureProcessedAt = 20_000))
                }
                val candidates = db.saleDao().retentionBatch(Long.MIN_VALUE, "", 1000, 256)
                assertThat(candidates.map { it.id }).containsExactly("old", "unknown")
                env.container.history.prune(1, 86_401_000)
                assertThat(db.saleDao().header("old")).isNull()
                assertThat(db.saleDao().header("unknown")?.status).isEqualTo(SaleStatus.UNKNOWN)
                assertThat(db.saleDao().header("recent-capture")).isNotNull()
                assertThat(db.saleDao().header("recent-599")).isNotNull()
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun `sale status writes never fetch line or tender relations`() {
        val queries = CopyOnWriteArrayList<String>()
        val env = TestEnvironment(queryCallback = queries::add)
        try {
            await {
                val db = env.container.database
                db.saleDao().insert(sale("sale"))
                queries.clear()
                env.container.sales.record("sale", SaleEvent.Interrupted)
                assertThat(queries.any { it.contains("FROM `sale_lines`") || it.contains("FROM `sale_tenders`") }).isFalse()
                assertThat(queries.any { it.contains("SELECT * FROM sales WHERE id") }).isTrue()
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun `all catalog streams share concurrent readers and commands read fresh inside transactions`() {
        val queries = CopyOnWriteArrayList<String>()
        val env = TestEnvironment(queryCallback = queries::add)
        try {
            await {
                coroutineScope {
                    val catalog = env.container.catalog
                    val streams = listOf(catalog.products, catalog.categories, catalog.taxRates)
                    val jobs = streams.flatMap { flow -> List(2) { launch { flow.collect() } } }
                    while (queries.count { it.startsWith("SELECT * FROM products ORDER") } == 0 ||
                        queries.count { it.startsWith("SELECT * FROM categories ORDER") } == 0 ||
                        queries.count { it.startsWith("SELECT * FROM tax_rates ORDER") } == 0
                    ) {
                        delay(10)
                    }
                    delay(50)
                    for (table in listOf("products", "categories", "tax_rates")) {
                        assertThat(queries.count { it.startsWith("SELECT * FROM $table ORDER") }).isEqualTo(1)
                    }
                    env.container.database.withTransaction {
                        val id =
                            env.container.database
                                .catalogDao()
                                .insert(TaxRateEntity(name = "Fresh", rateMilliPercent = 0))
                        assertThat(catalog.reader.taxRates().any { it.id == id }).isTrue()
                        env.container.database
                            .catalogDao()
                            .insert(ProductEntity(name = "Fresh", priceMinor = 100, taxRateId = id))
                        assertThat(catalog.reader.products().map { it.name }).contains("Fresh")
                        assertThat(catalog.reader.categories()).isEmpty()
                    }
                    jobs.forEach { it.cancelAndJoin() }
                }
            }
        } finally {
            env.close()
        }
    }

    @Test
    fun `sample cleanup callback runs after catalog and sales commit together`() {
        val env = TestEnvironment()
        try {
            val db = env.container.database
            await { db.saleDao().insert(sale("sample").copy(sample = true)) }
            var notified = false
            val history =
                HistoryRepository(db, onRemoved = { rows, _ ->
                    assertThat(db.inTransaction()).isFalse()
                    assertThat(rows.map { it.id }).containsExactly("sample")
                    notified = true
                })
            await { history.removeSamples { assertThat(db.inTransaction()).isTrue() } }
            assertThat(notified).isTrue()
            assertThat(await { db.saleDao().header("sample") }).isNull()
        } finally {
            env.close()
        }
    }
}
