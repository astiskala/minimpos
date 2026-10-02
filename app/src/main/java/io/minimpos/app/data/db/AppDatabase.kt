package io.minimpos.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.minimpos.app.R

/**
 * The app's Room database, `minimpos.db`: the catalogue, sales and refunds.
 *
 * Schema history (exported to `app/schemas/`): version 2 added `sales.errorCondition` and `sales.refusalReason`;
 * version 3 made `products.taxRateId` optional; version 4 made it required again ([TaxRateRequiredMigration]); version
 * 5 added `products.kind`, `sales.kind` (both [SaleKind.SALE] for existing rows) and `refunds.cancellation`; version 6
 * added tipping on the receipt and captures (`sales.tipOnReceipt`, `tipMinor`, `authorisedMinor`, `adjustment`,
 * `adjustAuthorisationData`, `capturedMinor`, `captureStatus`, `modificationMessage`); version 7 added
 * `sales.paymentMethodVariant`, which names the wallet of a card in one; version 8 added `sales.holdCancelled`
 * ([HoldCancelledMigration]); version 9 added payment links (`sales.paymentLink`, `paymentLinkId`, `paymentLinkUrl`,
 * `paymentLinkExpiresAt`); version 10 added the typed [StoredReason]s (`sales.reason`, `sales.modificationReason`,
 * `refunds.reason`). Versions 1 to 3, 4 to 7 and 8 to 10 are Room auto-migrations. There is no destructive fallback,
 * so every schema change needs a migration.
 */
@TypeConverters(StoredReasonConverter::class)
@Database(
    entities = [
        TaxRateEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        RefundEntity::class,
    ],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    /** Tax rates, categories and products. */
    abstract fun catalogDao(): CatalogDao

    /** Sales and their lines. */
    abstract fun saleDao(): SaleDao

    /** Refunds. */
    abstract fun refundDao(): RefundDao

    /** Opening the database with its migrations. */
    companion object {
        /** Adds the hand-written migrations to [builder]; the others are Room auto-migrations. */
        fun withMigrations(
            builder: RoomDatabase.Builder<AppDatabase>,
            context: Context,
        ): RoomDatabase.Builder<AppDatabase> =
            builder.addMigrations(TaxRateRequiredMigration(context.getString(R.string.tax_default_zero)), HoldCancelledMigration)

        /** Opens (lazily, on first query) the on-disk database with all migrations. */
        fun create(context: Context): AppDatabase =
            withMigrations(Room.databaseBuilder(context, AppDatabase::class.java, "minimpos.db"), context).build()
    }
}
