package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.app.terminal.TerminalState
import app.minimpos.terminal.checkout.StoredCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * One memory-only saved-card lookup reading.
 *
 * @property reference Shopper reference actually queried; null before lookup or after invalidation.
 * @property finding Whether the current reading is being queried.
 * @property cards All listed cards in Adyen's order; null before a current answer.
 * @property eligible Listed cards supporting the current recurring model; the only cards that may start a charge.
 * @property failure Typed lookup failure; null before lookup or after success.
 * @property blocked Why lookup is unavailable; null when setup and shopper-reference configuration allow it.
 */
data class SavedCardLookupState(
    val reference: String? = null,
    val finding: Boolean = false,
    val cards: List<StoredCard>? = null,
    val eligible: List<StoredCard> = emptyList(),
    val failure: Failure? = null,
    val blocked: Failure? = null,
) {
    /** Whether [card]'s identifier belongs to the current chargeable reading, including a screen's restored card choice. */
    fun chargeable(card: StoredCard): Boolean = eligible.any { it.id == card.id }

    /** Why a restored [card] cannot be chosen now; a lost or invalidated listing requires finding cards again. */
    fun selectionProblem(card: StoredCard): Failure? =
        blocked ?: Failure.NotSetUp(SetupProblem.SETUP_CHANGED).takeUnless { chargeable(card) }
}

/**
 * Saved-card listing freshness and selection, shared by checkout's automatic lookup and Charge token's explicit one.
 * Changed shopper reference, recurring model, readiness or target invalidates the whole reading. Late answers cannot
 * revive it, and starts retain the listing's original payment context even if they are queued behind setup changes.
 *
 * @param scope Owns observation and read-only lookups; cancelling it abandons lookups, never a started charge.
 * @param checkouts Coherent session readings; only reference, recurring model and payment kind affect lookup identity.
 * @param terminal Current lookup readiness.
 * @param targets Current targets, including credential changes.
 * @param charges Lists cards and starts persisted financial operations.
 * @param lookupDelay Null requires [find]; otherwise wait this long after an eligible reading changes.
 */
class SavedCardLookup internal constructor(
    private val scope: CoroutineScope,
    checkouts: Flow<Checkout>,
    terminal: Flow<TerminalState>,
    targets: Flow<ApiTarget>,
    private val charges: SavedCardCharges,
    private val lookupDelay: Duration?,
) {
    private data class Query(
        val reference: String?,
        val model: String,
        val origin: ApiTarget,
        val blocked: Failure?,
    ) {
        val available: Boolean get() = reference != null && blocked == null

        fun matches(start: SavedCardStart): Boolean = start.payment.shopperReference == reference && start.recurringModel == model
    }

    // Each emitted reading is a new object, so identity tells a reading from an equal later one.
    @Volatile private var current: Query? = null

    /** Current listing, cleared before a changed reading can be queried. */
    val state: StateFlow<SavedCardLookupState>
        field = MutableStateFlow(SavedCardLookupState())

    init {
        scope.launch {
            combine(checkouts, terminal, targets) { checkout, status, origin ->
                Query(
                    checkout.shopperReference.takeUnless { checkout.preAuthorisation },
                    checkout.payment.recurringModel().value,
                    origin,
                    SavedCardCharges.blocked(checkout.payment.shopperReferenceSource, status),
                )
            }.distinctUntilChanged().collectLatest { query ->
                synchronized(this@SavedCardLookup) {
                    current = query
                    state.value = SavedCardLookupState(blocked = query.blocked)
                }
                if (lookupDelay != null && query.reference != null && query.blocked == null) {
                    delay(lookupDelay)
                    if (begin(query)) fetch(query)
                }
            }
        }
    }

    /** Queries the current valid reference explicitly; duplicate requests are ignored while that reading is being found. */
    fun find() {
        val query = current ?: return
        if (begin(query)) scope.launch { fetch(query) }
    }

    /**
     * Starts only a charge made from the current reading's shopper reference, recurring model and eligible card.
     * Returns its sale ID, or null while stale, blocked, unlisted or another charge is running.
     */
    fun start(start: SavedCardStart): String? =
        synchronized(this) {
            val query = current ?: return null
            val reading = state.value
            val card = reading.eligible.firstOrNull { it.id == start.card.id } ?: return null
            if (!query.available || reading.finding) return null
            if (reading.reference != query.reference || !query.matches(start)) return null
            charges.start(start.forLookup(card, query.origin.context))
        }

    private fun begin(query: Query): Boolean =
        synchronized(this) {
            if (current !== query || !query.available || state.value.finding) {
                false
            } else {
                state.value = SavedCardLookupState(reference = query.reference, finding = true)
                true
            }
        }

    private suspend fun fetch(query: Query) {
        val found = charges.cards(checkNotNull(query.reference), query.origin)
        synchronized(this) {
            if (current !== query) return
            state.value =
                when (found) {
                    is CardLookup.Listed -> {
                        SavedCardLookupState(
                            reference = query.reference,
                            cards = found.cards,
                            eligible = found.cards.filter { it.supports(query.model) },
                        )
                    }

                    is CardLookup.NotDone -> {
                        SavedCardLookupState(reference = query.reference, failure = found.failure)
                    }
                }
        }
    }
}
