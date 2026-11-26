package app.minimpos.app.history

import app.minimpos.app.data.repo.HistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

// ponytail: folds all retained history after each write while collected; an indexed count query if history outgrows it.

/**
 * How many retained records need attention, counted again after each history write: the records History's "Needs
 * attention" filter shows ([HistoryAccounting.needsAttention]). Cold; counting runs off the main thread, and a write
 * during a count starts it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun HistoryRepository.attention(): Flow<Int> =
    changes.mapLatest { reader.fold(0) { count, batch -> count + batch.count(HistoryAccounting::needsAttention) } }
