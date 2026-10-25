package app.minimpos.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.minimpos.app.data.db.AppDatabase
import org.json.JSONObject
import java.io.File

/** The `database` object of the Room schema the build exported for [version]. */
internal fun schemaJson(version: Int): JSONObject =
    listOf("schemas", "app/schemas")
        .map { File(it, "app.minimpos.app.data.db.AppDatabase/$version.json") }
        .first { it.exists() }
        .let { JSONObject(it.readText()).getJSONObject("database") }

/** Creates database [name] exactly as released schema [version] defined it, with its tables, indices and version. */
internal fun createSchemaDatabase(
    context: Context,
    name: String,
    version: Int,
) {
    val schema = schemaJson(version)
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

/** Opens database [name] with every registered migration, runs [block] and closes it. */
internal fun <T> withMigratedDatabase(
    context: Context,
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
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
            ).allowMainThreadQueries()
            .build()
    try {
        return block(db)
    } finally {
        db.close()
    }
}
