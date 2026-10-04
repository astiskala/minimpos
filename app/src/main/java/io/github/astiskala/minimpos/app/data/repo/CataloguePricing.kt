package io.github.astiskala.minimpos.app.data.repo

import androidx.room.withTransaction
import io.github.astiskala.minimpos.app.data.db.AppDatabase

/** Atomic catalogue unit-price writes for a confirmed, recoverable pricing journal. */
class CataloguePricing(
    private val db: AppDatabase,
) {
    /** Absolute positive minor-unit targets, keyed by catalogue row ID; replay is idempotent. */
    suspend fun apply(prices: Map<Long, Long>) =
        db.withTransaction {
            require(prices.values.all { it > 0 }) { "Prices must remain positive" }
            val dao = db.catalogDao()
            dao.productsOnce().forEach { product -> prices[product.id]?.let { dao.update(product.copy(priceMinor = it)) } }
        }
}
