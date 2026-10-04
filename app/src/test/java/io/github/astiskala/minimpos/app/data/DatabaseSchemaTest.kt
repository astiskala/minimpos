package io.github.astiskala.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.db.StoredReason
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DatabaseSchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun exportedSchema(): JSONObject =
        listOf("schemas", "app/schemas")
            .map { File(it, "io.github.astiskala.minimpos.app.data.db.AppDatabase/10.json") }
            .first { it.exists() }
            .let { JSONObject(it.readText()).getJSONObject("database") }

    private fun createExportedDatabase(name: String) {
        val schema = exportedSchema()
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (position in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                    )
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) db.execSQL(queries.getString(index))
            db.version = schema.getInt("version")
        }
    }

    private fun <T> withDatabase(
        name: String,
        block: (AppDatabase) -> T,
    ): T {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the exported current schema opens and retains catalogue and typed sale outcomes`() {
        val name = "schema-${UUID.randomUUID()}"
        createExportedDatabase(name)
        try {
            withDatabase(name) { db ->
                runBlocking {
                    val taxId = db.catalogDao().insert(TaxRateEntity(name = "No tax", rateMilliPercent = 0))
                    db.catalogDao().insert(ProductEntity(name = "Item", priceMinor = 400, taxRateId = taxId))
                    db.saleDao().insert(
                        SaleEntity(
                            id = "sale",
                            createdAt = 1,
                            currency = "AUD",
                            taxMode = "INCLUSIVE",
                            netMinor = 400,
                            taxMinor = 0,
                            totalMinor = 400,
                            status = SaleStatus.UNKNOWN,
                            merchantReference = "schema-sale",
                            reason = StoredReason.NotSetUp(SetupProblem.API_KEY),
                        ),
                    )
                }
            }
            withDatabase(name) { db ->
                runBlocking {
                    assertThat(
                        db
                            .catalogDao()
                            .productsOnce()
                            .single()
                            .name,
                    ).isEqualTo("Item")
                    assertThat(
                        db
                            .saleDao()
                            .sale("sale")!!
                            .sale.reason,
                    ).isEqualTo(StoredReason.NotSetUp(SetupProblem.API_KEY))
                    assertThat(db.openHelper.readableDatabase.version).isEqualTo(exportedSchema().getInt("version"))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
