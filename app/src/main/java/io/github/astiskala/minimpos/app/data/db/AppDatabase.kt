package io.github.astiskala.minimpos.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 10,
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
        /** Opens lazily on first query; no earlier-schema migration or destructive fallback is configured. */
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "minimpos.db").build()
    }
}
