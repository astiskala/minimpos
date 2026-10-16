package app.minimpos.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The current Room database, `minimpos.db`: catalogue, sales and refunds. Its schema is exported to `app/schemas/`. */
@TypeConverters(StoredReasonConverter::class, PaymentContextConverter::class)
@Database(
    entities = [
        TaxRateEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        CatalogImportEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        RefundEntity::class,
        HistorySwitchEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /** Tax rates, categories and products. */
    abstract fun catalogDao(): CatalogDao

    /** Sales and their lines. */
    abstract fun saleDao(): SaleDao

    /** Refunds. */
    abstract fun refundDao(): RefundDao

    /** Opening the current database. */
    companion object {
        /** Preserves all released 1.0.x version-10 data when upgrading to version 11. */
        val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `sales` ADD COLUMN `poiReconciliationId` TEXT")
                    db.execSQL("ALTER TABLE `refunds` ADD COLUMN `poiReconciliationId` TEXT")
                }
            }

        /** Opens lazily on first query with versioned migrations; no destructive fallback is configured. */
        fun create(context: Context): AppDatabase =
            Room
                .databaseBuilder(context, AppDatabase::class.java, "minimpos.db")
                .addMigrations(MIGRATION_10_11)
                .build()
    }
}
