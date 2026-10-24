package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
class DatabaseSchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun exportedSchema(version: Int = 17): JSONObject =
        listOf("schemas", "app/schemas")
            .map { File(it, "app.minimpos.app.data.db.AppDatabase/$version.json") }
            .first { it.exists() }
            .let { JSONObject(it.readText()).getJSONObject("database") }

    private fun createExportedDatabase(
        name: String,
        version: Int = 17,
    ) {
        val schema = exportedSchema(version)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0..<entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (position in 0..<indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                    )
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0..<queries.length()) db.execSQL(queries.getString(index))
            db.version = schema.getInt("version")
        }
    }

    private fun <T> withDatabase(
        name: String,
        block: (AppDatabase) -> T,
    ): T {
        val db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                    AppDatabase.MIGRATION_12_13,
                    AppDatabase.MIGRATION_13_14,
                    AppDatabase.MIGRATION_14_15,
                    AppDatabase.MIGRATION_15_16,
                    AppDatabase.MIGRATION_16_17,
                ).allowMainThreadQueries()
                .build()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `version 16 migration preserves unknown payment identity and stores the next audio request`() {
        val name = "schema-v16-${Uuid.random()}"
        createExportedDatabase(name, 16)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO sales (id, sample, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, " +
                    "status, merchantReference, tokenizationRequested, signatureRequired, refundedMinor, kind, " +
                    "tipOnReceipt, holdCancelled, paymentLink, adjustmentPending, serviceId, customerReceiptJson) " +
                    "VALUES ('unknown', 0, 100, 'AUD', 'INCLUSIVE', 100, 0, 100, 'UNKNOWN', 'MP-16', " +
                    "0, 0, 0, 'SALE', 0, 0, 0, 0, 'original-service', '[original-receipt]')",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val original = checkNotNull(db.saleDao().sale("unknown")).sale
                    assertThat(original.audioGuidance).isFalse()
                    assertThat(original.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(original.serviceId).isEqualTo("original-service")
                    assertThat(original.customerReceiptJson).isEqualTo("[original-receipt]")
                    db.saleDao().insert(original.copy(id = "audio", audioGuidance = true))
                    assertThat(
                        db
                            .saleDao()
                            .sale("audio")!!
                            .sale.audioGuidance,
                    ).isTrue()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `version 15 shopper index migration preserves tokens receipts and unknown operation identities`() {
        val name = "schema-v15-${Uuid.random()}"
        createExportedDatabase(name, 15)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO sales (id, sample, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, " +
                    "status, merchantReference, tokenizationRequested, signatureRequired, refundedMinor, kind, " +
                    "tipOnReceipt, holdCancelled, paymentLink, adjustmentPending, serviceId, customerReceiptJson, " +
                    "shopperReference, storedPaymentMethodId) " +
                    "VALUES ('token-sale', 0, 100, 'AUD', 'INCLUSIVE', 100, 0, 100, 'UNKNOWN', 'MP-15', " +
                    "1, 0, 0, 'SALE', 0, 0, 0, 0, 'original-service', '[original-receipt]', 'shopper', 'token')",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val original = checkNotNull(db.saleDao().sale("token-sale")).sale
                    assertThat(original.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(original.serviceId).isEqualTo("original-service")
                    assertThat(original.customerReceiptJson).isEqualTo("[original-receipt]")
                    val batch = db.saleDao().shopperSales("shopper", Long.MAX_VALUE, "", 256)
                    assertThat(batch.single().storedPaymentMethodId).isEqualTo("token")
                    assertThat(batch.single().customerReceiptJson).isNull()
                    assertThat(db.saleDao().shopperSales("other", Long.MAX_VALUE, "", 256)).isEmpty()
                    db.openHelper.readableDatabase
                        .query(
                            "EXPLAIN QUERY PLAN SELECT id FROM sales WHERE shopperReference = 'shopper' " +
                                "AND createdAt <= 100 ORDER BY createdAt DESC, id DESC LIMIT 256",
                        ).use { cursor ->
                            val details = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                            assertThat(details.joinToString()).contains("index_sales_shopperReference_createdAt_id")
                            assertThat(details.joinToString()).doesNotContain("TEMP B-TREE")
                        }
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `version 14 index migration preserves financial recovery and receipt data`() {
        val name = "schema-v14-${Uuid.random()}"
        createExportedDatabase(name, 14)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO sales (id, sample, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, " +
                    "status, merchantReference, tokenizationRequested, signatureRequired, refundedMinor, kind, " +
                    "tipOnReceipt, holdCancelled, paymentLink, adjustmentPending, serviceId, customerReceiptJson, " +
                    "processedAt, captureStartedAt, captureProcessedAt, adjustmentStartedAt) " +
                    "VALUES ('recovery', 0, 100, 'AUD', 'INCLUSIVE', 100, 0, 100, 'UNKNOWN', 'MP-14', " +
                    "0, 0, 0, 'SALE', 0, 0, 0, 0, 'service14', '[receipt]', 200, 300, 400, 500)",
            )
            raw.execSQL(
                "INSERT INTO refunds (id, saleId, createdAt, merchantReference, originalTransactionId, " +
                    "originalTimestamp, currency, amountMinor, full, status, serviceId) " +
                    "VALUES ('refund14', 'recovery', 600, 'R-14', 'T.PSP', 'timestamp', 'AUD', 50, 0, 'UNKNOWN', 'refund-service')",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("recovery")).sale
                    assertThat(sale.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(sale.serviceId).isEqualTo("service14")
                    assertThat(sale.customerReceiptJson).isEqualTo("[receipt]")
                    assertThat(sale.processedAt).isEqualTo(200)
                    assertThat(sale.captureStartedAt).isEqualTo(300)
                    assertThat(sale.captureProcessedAt).isEqualTo(400)
                    assertThat(sale.adjustmentStartedAt).isEqualTo(500)
                    assertThat(db.refundDao().refund("refund14")?.serviceId).isEqualTo("refund-service")
                    val indexes = mutableListOf<String>()
                    db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
                        while (cursor.moveToNext()) indexes += cursor.getString(0)
                    }
                    assertThat(indexes).containsAtLeast(
                        "index_sales_processedAt_id",
                        "index_sales_captureProcessedAt_id",
                        "index_sales_captureStartedAt_id",
                        "index_refunds_processedAt_id",
                    )
                    val keys = db.saleDao().historyKeys(Long.MAX_VALUE, "z", Long.MIN_VALUE, 256)
                    assertThat(keys.map { it.at }).containsExactly(600L, 400L, 300L, 200L, 100L).inOrder()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `the exported current schema opens and retains catalogue and typed sale outcomes`() {
        val name = "schema-${Uuid.random()}"
        createExportedDatabase(name)
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val taxId = db.catalogDao().insert(TaxRateEntity(name = "No tax", rateMilliPercent = 0))
                    db.catalogDao().insert(ProductEntity(name = "Item", priceMinor = 400, taxRateId = taxId, sample = true))
                    db.saleDao().insert(
                        SaleEntity(
                            id = "sale",
                            sample = true,
                            createdAt = 1,
                            currency = "AUD",
                            taxMode = "INCLUSIVE",
                            netMinor = 400,
                            taxMinor = 0,
                            totalMinor = 400,
                            status = SaleStatus.UNKNOWN,
                            merchantReference = "schema-sale",
                            requestedWallet = "wechatpay_pos",
                            reason = StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)),
                            processedAt = 10,
                            captureStartedAt = 20,
                            adjustmentStartedAt = 15,
                            captureProcessedAt = 30,
                        ),
                    )
                }
            }
            withDatabase(name) { db ->
                runBlocking {
                    val product = db.catalogDao().productsOnce().single()
                    val sale = checkNotNull(db.saleDao().sale("sale")).sale
                    assertThat(product.sample).isTrue()
                    assertThat(product.name).isEqualTo("Item")
                    assertThat(sale.sample).isTrue()
                    assertThat(sale.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.API_KEY)))
                    assertThat(sale.requestedWallet).isEqualTo("wechatpay_pos")
                    assertThat(sale.processedAt).isEqualTo(10)
                    assertThat(sale.captureStartedAt).isEqualTo(20)
                    assertThat(sale.adjustmentStartedAt).isEqualTo(15)
                    assertThat(sale.captureProcessedAt).isEqualTo(30)
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(exportedSchema().getInt("version"))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrates version 10 database to version 11 preserving existing sales and catalogue`() {
        val name = "schema-v10-${Uuid.random()}"
        createExportedDatabase(name, version = 10)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("INSERT INTO `tax_rates` (`id`, `name`, `rateMilliPercent`, `sortOrder`) VALUES (1, 'GST', 10000, 0)")
            raw.execSQL(
                "INSERT INTO `sales` (`id`, `sample`, `createdAt`, `currency`, `taxMode`, `netMinor`, `taxMinor`, `totalMinor`, " +
                    "`status`, `merchantReference`, `tokenizationRequested`, `signatureRequired`, `refundedMinor`, `kind`, " +
                    "`tipOnReceipt`, `holdCancelled`, `paymentLink`, `adjustmentPending`) " +
                    "VALUES ('s10', 0, 100, 'AUD', 'INCLUSIVE', 909, 91, 1000, 'APPROVED', 'MP-10', 0, 0, 0, 'SALE', 0, 0, 0, 0)",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("s10")).sale
                    assertThat(sale.totalMinor).isEqualTo(1_000)
                    assertThat(sale.merchantReference).isEqualTo("MP-10")
                    assertThat(sale.poiReconciliationId).isNull()
                    assertThat(sale.savedCard).isFalse()
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(17)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrates version 11 database to version 12 preserving sales, unfinished refunds and catalogue`() {
        val name = "schema-v11-${Uuid.random()}"
        createExportedDatabase(name, version = 11)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("INSERT INTO `tax_rates` (`id`, `name`, `rateMilliPercent`, `sortOrder`) VALUES (1, 'GST', 10000, 0)")
            raw.execSQL(
                "INSERT INTO `sales` (`id`, `sample`, `createdAt`, `currency`, `taxMode`, `netMinor`, `taxMinor`, `totalMinor`, " +
                    "`status`, `merchantReference`, `tokenizationRequested`, `signatureRequired`, `refundedMinor`, `kind`, " +
                    "`tipOnReceipt`, `holdCancelled`, `paymentLink`, `adjustmentPending`, `paidMinor`, `storedPaymentMethodId`) " +
                    "VALUES ('s11', 0, 100, 'AUD', 'INCLUSIVE', 909, 91, 1000, 'UNKNOWN', 'MP-11', 1, 0, 0, 'SALE', " +
                    "0, 0, 0, 0, 0, 'TOKEN')",
            )
            raw.execSQL(
                "INSERT INTO `refunds` (`id`, `saleId`, `createdAt`, `merchantReference`, `originalTransactionId`, " +
                    "`originalTimestamp`, `currency`, `amountMinor`, `full`, `status`, `cancellation`) " +
                    "VALUES ('r11', 's11', 200, 'R-11', 'T.PSP', '2026-01-01T00:00:00Z', 'AUD', 500, 0, 'UNKNOWN', 0)",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("s11")).sale
                    assertThat(sale.status).isEqualTo(SaleStatus.UNKNOWN)
                    assertThat(sale.storedPaymentMethodId).isEqualTo("TOKEN")
                    assertThat(sale.savedCard).isFalse()
                    assertThat(sale.chargedCardId).isNull()
                    assertThat(sale.chargeRecurringModel).isNull()
                    val refund = checkNotNull(db.refundDao().refund("r11"))
                    assertThat(refund.amountMinor).isEqualTo(500)
                    assertThat(refund.checkoutRefund).isFalse()
                    assertThat(
                        db
                            .catalogDao()
                            .taxRatesOnce()
                            .single()
                            .name,
                    ).isEqualTo("GST")
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(17)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrates version 12 database to version 13 preserving sales and their busy refusals`() {
        val name = "schema-v12-${Uuid.random()}"
        createExportedDatabase(name, version = 12)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("INSERT INTO `tax_rates` (`id`, `name`, `rateMilliPercent`, `sortOrder`) VALUES (1, 'GST', 10000, 0)")
            raw.execSQL(
                "INSERT INTO `sales` (`id`, `sample`, `createdAt`, `currency`, `taxMode`, `netMinor`, `taxMinor`, `totalMinor`, " +
                    "`status`, `merchantReference`, `tokenizationRequested`, `signatureRequired`, `refundedMinor`, `kind`, " +
                    "`tipOnReceipt`, `holdCancelled`, `paymentLink`, `adjustmentPending`, `paidMinor`, `savedCard`, " +
                    "`errorCondition`) " +
                    "VALUES ('s12', 0, 100, 'AUD', 'INCLUSIVE', 909, 91, 1000, 'DECLINED', 'MP-12', 0, 0, 0, 'SALE', " +
                    "0, 0, 0, 0, 0, 0, 'Busy')",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("s12")).sale
                    assertThat(sale.status).isEqualTo(SaleStatus.DECLINED)
                    assertThat(sale.errorCondition).isEqualTo("Busy")
                    assertThat(sale.busyServiceId).isNull()
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(17)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `version 13 preserves unresolved adjustments without inventing a retry window`() {
        val name = "schema-v13-${Uuid.random()}"
        createExportedDatabase(name, version = 13)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL(
                "INSERT INTO `sales` (`id`, `sample`, `createdAt`, `currency`, `taxMode`, `netMinor`, `taxMinor`, `totalMinor`, " +
                    "`status`, `merchantReference`, `tokenizationRequested`, `signatureRequired`, `refundedMinor`, `kind`, " +
                    "`tipOnReceipt`, `holdCancelled`, `paymentLink`, `adjustmentPending`, `paidMinor`, `savedCard`, " +
                    "`adjustmentKey`, `adjustmentAmountMinor`, `captureStartedAt`, `captureStatus`, `capturedMinor`) " +
                    "VALUES ('s13', 0, 100, 'AUD', 'INCLUSIVE', 909, 91, 1000, 'APPROVED', 'MP-13', 0, 0, 0, 'PRE_AUTHORISATION', " +
                    "0, 0, 0, 1, 0, 0, 'original-key', 1200, 200, 'UNKNOWN', 1200)",
            )
        }
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val sale = checkNotNull(db.saleDao().sale("s13")).sale
                    assertThat(sale.adjustmentPending).isTrue()
                    assertThat(sale.adjustmentKey).isEqualTo("original-key")
                    assertThat(sale.adjustmentAmountMinor).isEqualTo(1200)
                    assertThat(sale.adjustmentStartedAt).isNull()
                    assertThat(sale.captureStartedAt).isEqualTo(200)
                    assertThat(sale.captureStatus?.name).isEqualTo("UNKNOWN")
                    assertThat(sale.totalMinor).isEqualTo(1000)
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(17)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
