package io.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.db.StoredReason
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opens a database created from an exported older schema, so Room's auto-migrations run on real data. */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun createDatabase(
        name: String,
        version: Int,
        rows: SQLiteDatabase.() -> Unit,
    ) {
        context.deleteDatabase(name)
        val schema =
            listOf("schemas", "app/schemas")
                .map { File(it, "io.minimpos.app.data.db.AppDatabase/$version.json") }
                .first { it.exists() }
                .let { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.rows()
            db.version = version
        }
    }

    private fun <T> migrated(
        name: String,
        block: suspend (AppDatabase) -> T,
    ): T {
        val db =
            AppDatabase
                .withMigrations(Room.databaseBuilder(context, AppDatabase::class.java, name), context)
                .allowMainThreadQueries()
                .build()
        try {
            return runBlocking { block(db) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun SQLiteDatabase.insertProduct(
        id: Long,
        name: String,
        taxRateId: Long?,
    ) = execSQL(
        "INSERT INTO products (id, name, priceMinor, taxRateId, categoryId, sku, sortOrder) VALUES (?, ?, 100, ?, NULL, NULL, 0)",
        arrayOf<Any?>(id, name, taxRateId),
    )

    @Test
    fun `version 2 products keep their tax rate`() {
        val name = "migration-v2.db"
        createDatabase(name, 2) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            execSQL(
                "INSERT INTO products (id, name, priceMinor, taxRateId, categoryId, sku, sortOrder) " +
                    "VALUES (1, 'Latte', 450, 1, NULL, 'L1', 0)",
            )
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            assertThat(dao.productsOnce().single()).isEqualTo(ProductEntity(1, "Latte", 450, 1, null, "L1", 0))
            assertThat(dao.productCountForTaxRate(1)).isEqualTo(1)
            assertThat(dao.taxRatesOnce()).hasSize(1)
        }
    }

    @Test
    fun `version 3 untaxed products move to a new zero rate`() {
        val name = "migration-v3-new.db"
        createDatabase(name, 3) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            insertProduct(1, "Latte", 1)
            insertProduct(2, "Stamp", null)
            insertProduct(3, "Card", null)
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            val zero = dao.taxRatesOnce().single { it.rateMilliPercent == 0 }
            assertThat(zero.name).isEqualTo("No tax")
            assertThat(zero.sortOrder).isEqualTo(1)
            assertThat(dao.productsOnce().associate { it.name to it.taxRateId })
                .containsExactly("Latte", 1L, "Stamp", zero.id, "Card", zero.id)
            // New products carry on after the existing ids.
            assertThat(dao.insert(ProductEntity(name = "Tea", priceMinor = 350, taxRateId = 1))).isEqualTo(4)
        }
    }

    @Test
    fun `version 4 products, sales and refunds become sale ones`() {
        val name = "migration-v4.db"
        createDatabase(name, 4) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            insertProduct(1, "Latte", 1)
            execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor) VALUES ('s1', 1, 'AUD', 'INCLUSIVE', 91, 9, 100, " +
                    "'APPROVED', 'MP-1', 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, currency, " +
                    "amountMinor, full, status) VALUES ('r1', 's1', 2, 'R-1', 'T.P', '2026-09-30T01:02:03.456Z', 'AUD', 100, 1, " +
                    "'REQUESTED')",
            )
        }
        migrated(name) { db ->
            assertThat(
                db
                    .catalogDao()
                    .productsOnce()
                    .single()
                    .kind,
            ).isEqualTo(SaleKind.SALE)
            assertThat(
                db
                    .saleDao()
                    .sale("s1")!!
                    .sale.kind,
            ).isEqualTo(SaleKind.SALE)
            assertThat(db.refundDao().refund("r1")!!.cancellation).isFalse()
        }
    }

    @Test
    fun `version 5 sales are neither taken for a tip nor captured`() {
        val name = "migration-v5.db"
        createDatabase(name, 5) {
            execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, kind) VALUES ('s1', 1, 'AUD', 'INCLUSIVE', 91, 9, 100, " +
                    "'APPROVED', 'MP-1', 0, 0, 0, 'PRE_AUTHORISATION')",
            )
        }
        migrated(name) { db ->
            val sale = db.saleDao().sale("s1")!!.sale
            assertThat(sale.kind).isEqualTo(SaleKind.PRE_AUTHORISATION)
            assertThat(sale.tipOnReceipt).isFalse()
            assertThat(sale.tipMinor).isNull()
            assertThat(sale.captureStatus).isNull()
            assertThat(sale.authorisedMinor).isNull()
            assertThat(sale.amountMinor).isEqualTo(100)
            assertThat(db.saleDao().pendingCaptures()).isEmpty()
        }
    }

    @Test
    fun `version 6 sales have no payment method variant`() {
        val name = "migration-v6.db"
        createDatabase(name, 6) {
            execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, kind, tipOnReceipt, paymentBrand) VALUES ('s1', 1, " +
                    "'AUD', 'INCLUSIVE', 91, 9, 100, 'APPROVED', 'MP-1', 0, 0, 0, 'SALE', 0, 'visa')",
            )
        }
        migrated(name) { db ->
            val sale = db.saleDao().sale("s1")!!.sale
            assertThat(sale.paymentBrand).isEqualTo("visa")
            assertThat(sale.paymentMethodVariant).isNull()
        }
    }

    @Test
    fun `version 7 cancellations become cancelled holds that refunded nothing`() {
        val name = "migration-v7.db"
        createDatabase(name, 7) {
            listOf("c1", "c2", "s1").forEach { id ->
                execSQL(
                    "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                        "tokenizationRequested, signatureRequired, refundedMinor, kind, tipOnReceipt) VALUES ('$id', 1, 'AUD', " +
                        "'INCLUSIVE', 91, 9, 100, 'APPROVED', 'MP-$id', 0, 0, 100, " +
                        "'${if (id == "s1") "SALE" else "PRE_AUTHORISATION"}', 0)",
                )
                execSQL(
                    "INSERT INTO sale_lines (saleId, position, productId, name, sku, unitPriceMinor, quantity, taxName, " +
                        "taxRateMilliPercent, netMinor, taxMinor, grossMinor, refundedQuantity) VALUES ('$id', 0, NULL, 'A', NULL, " +
                        "100, 1, 'GST', 10000, 91, 9, 100, 1)",
                )
            }
            // c1 was cancelled; c2's cancellation failed; s1 was refunded in full.
            val refunds =
                listOf(Triple("r1", "c1", "1, 'REQUESTED'"), Triple("r2", "c2", "1, 'FAILED'"), Triple("r3", "s1", "0, 'REQUESTED'"))
            refunds.forEach { (refund, sale, outcome) ->
                execSQL(
                    "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                        "currency, amountMinor, full, cancellation, status) VALUES ('$refund', '$sale', 2, 'R', 'T.P', " +
                        "'2026-09-30T01:02:03.456Z', 'AUD', 100, 1, $outcome)",
                )
            }
        }
        migrated(name) { db ->
            val sales = listOf("c1", "c2", "s1").associateWith { db.saleDao().sale(it)!! }
            assertThat(sales.mapValues { it.value.sale.holdCancelled }).containsExactly("c1", true, "c2", false, "s1", false)
            assertThat(sales.mapValues { it.value.sale.refundedMinor }).containsExactly("c1", 0L, "c2", 100L, "s1", 100L)
            assertThat(
                sales.mapValues {
                    it.value.lines
                        .single()
                        .refundedQuantity
                },
            ).containsExactly("c1", 0, "c2", 1, "s1", 1)
        }
    }

    @Test
    fun `version 8 sales were taken on a terminal, not through a payment link`() {
        val name = "migration-v8.db"
        createDatabase(name, 8) {
            execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, kind, tipOnReceipt, holdCancelled) VALUES ('s1', 1, " +
                    "'AUD', 'INCLUSIVE', 91, 9, 100, 'APPROVED', 'MP-1', 0, 0, 0, 'SALE', 0, 0)",
            )
        }
        migrated(name) { db ->
            val sale = db.saleDao().sale("s1")!!.sale
            assertThat(sale.paymentLink).isFalse()
            assertThat(sale.paymentLinkId).isNull()
            assertThat(sale.paymentLinkUrl).isNull()
            assertThat(sale.paymentLinkExpiresAt).isNull()
        }
    }

    @Test
    fun `version 9 messages are kept as they were worded, with no typed reason`() {
        val name = "migration-v9.db"
        createDatabase(name, 9) {
            execSQL(
                "INSERT INTO sales (id, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status, merchantReference, " +
                    "tokenizationRequested, signatureRequired, refundedMinor, kind, tipOnReceipt, holdCancelled, paymentLink, " +
                    "message, modificationMessage) VALUES ('s1', 1, 'AUD', 'INCLUSIVE', 91, 9, 100, 'UNKNOWN', 'MP-1', 0, 0, 0, " +
                    "'SALE', 0, 0, 0, 'The app stopped before the result was received.', 'Refused')",
            )
            execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp, " +
                    "currency, amountMinor, full, cancellation, status, message) VALUES ('r1', 's1', 2, 'R', 'T.P', " +
                    "'2026-09-30T01:02:03.456Z', 'AUD', 100, 1, 0, 'FAILED', 'Enter the POIID')",
            )
        }
        migrated(name) { db ->
            val sale = db.saleDao().sale("s1")!!.sale
            assertThat(sale.message).isEqualTo("The app stopped before the result was received.")
            assertThat(sale.reason).isNull()
            assertThat(sale.modificationMessage).isEqualTo("Refused")
            assertThat(sale.modificationReason).isNull()
            val refund = db.refundDao().refund("r1")!!
            assertThat(refund.message).isEqualTo("Enter the POIID")
            assertThat(refund.reason).isNull()
            // A typed reason is stored and read back.
            db.saleDao().update(sale.copy(reason = StoredReason.NotSetUp(SetupProblem.HOST)))
            assertThat(
                db
                    .saleDao()
                    .sale("s1")!!
                    .sale.reason,
            ).isEqualTo(StoredReason.NotSetUp(SetupProblem.HOST))
        }
    }

    @Test
    fun `version 3 untaxed products use an existing zero rate`() {
        val name = "migration-v3-existing.db"
        createDatabase(name, 3) {
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (1, 'GST', 10000, 0)")
            execSQL("INSERT INTO tax_rates (id, name, rateMilliPercent, sortOrder) VALUES (2, 'GST-free', 0, 1)")
            insertProduct(1, "Stamp", null)
            insertProduct(2, "Water", 2)
        }
        migrated(name) { db ->
            val dao = db.catalogDao()
            assertThat(dao.taxRatesOnce().map { it.name }).containsExactly("GST", "GST-free")
            assertThat(dao.productsOnce().map { it.taxRateId }).containsExactly(2L, 2L)
        }
    }
}
