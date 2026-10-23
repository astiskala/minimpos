package app.minimpos.app

import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

/** Small-fixture history snapshots for tests; production always folds bounded records. */
@OptIn(ExperimentalCoroutinesApi::class)
fun HistoryRepository.items(): Flow<List<HistoryItem>> =
    changes.mapLatest { _ ->
        reader.fold(emptyList<HistoryItem>()) { all, batch -> all + batch }.sortedByDescending { it.createdAt }
    }
