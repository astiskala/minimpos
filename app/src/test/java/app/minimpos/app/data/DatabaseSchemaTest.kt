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

    private fun exportedSchema(version: Int = 14): JSONObject =
        listOf("schemas", "app/schemas")
            .map { File(it, "app.minimpos.app.data.db.AppDatabase/$version.json") }
            .first { it.exists() }
            .let { JSONObject(it.readText()).getJSONObject("database") }

    private fun createExportedDatabase(
        name: String,
        version: Int = 14,
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
                ).allowMainThreadQueries()
                .build()
        try {
            return block(db)
        } finally {
            db.close()
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
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(14)
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
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(14)
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
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(14)
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
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(14)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
