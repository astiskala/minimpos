package app.minimpos.app

import app.minimpos.app.data.db.SaleDao
import app.minimpos.app.data.db.SaleEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

/** Eager, small-fixture snapshots; production has no unbounded sale-list API. */
@OptIn(ExperimentalCoroutinesApi::class)
fun SaleDao.sales(): Flow<List<SaleEntity>> =
    historyChanges().mapLatest { _ ->
        val records = mutableListOf<SaleEntity>()
        var cursor = ""
        do {
            val batch = historyBatch(cursor, 256)
            batch.forEach { header(it.id)?.let(records::add) }
            cursor = batch.lastOrNull()?.id ?: cursor
        } while (batch.size == 256)
        records.sortedByDescending { it.createdAt }
    }
