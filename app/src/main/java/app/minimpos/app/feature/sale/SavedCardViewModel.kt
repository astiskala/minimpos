package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.SavedCardLookup
import app.minimpos.app.payment.SavedCardLookupState
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.payment.savedCardStart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.terminal.checkout.StoredCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.ZoneId

/**
 * What the saved-card screens show.
 *
 * @property checkout The saved-card session's checkout: its form holds the shopper reference typed, its cart the sale.
 * @property source What the shopper reference is made from, which decides the field asked for.
 * @property blocked Why cards cannot be found or charged now (no shopper reference configured, the Checkout API not set
 *   up, or a verified import still saving); null when they can.
 * @property finding Whether a lookup is running.
 * @property cards The cards found for [searchedReference]; null before a lookup.
 * @property failure Why the last lookup listed nothing; null when it worked.
 * @property searchedReference The shopper reference the [cards] belong to; a changed reference needs a new lookup.
 * @property recurringModel Adyen's `recurringProcessingModel` charges use; cards not saved for it cannot be chosen.
 * @property charge The charge running or finished; [TransactionState.Idle] before one starts.
 * @property lookup Current non-secret lookup reading, including selected-card eligibility.
 */
data class SavedCardUiState(
    val checkout: Checkout = Checkout(),
    val source: ShopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE,
    val blocked: Failure? = null,
    val finding: Boolean = false,
    val cards: List<StoredCard>? = null,
    val failure: Failure? = null,
    val searchedReference: String? = null,
    val recurringModel: String = "",
    val charge: TransactionState = TransactionState.Idle,
    val lookup: SavedCardLookupState = SavedCardLookupState(),
) {
    /** The shopper reference the typed field makes, as checkout makes it; null while it is not valid. */
    val shopperReference: String? get() = checkout.shopperReference

    /** The cards that may be charged with [recurringModel]; the others are listed but cannot be chosen. */
    fun chargeable(card: StoredCard): Boolean = lookup.chargeable(card)

    /** Why a selected [card] cannot be charged now; null while its original lookup remains usable. */
    fun selectionProblem(card: StoredCard): Failure? = lookup.selectionProblem(card)
}

/**
 * Finding a shopper's saved cards and charging one for the sale rung up in the saved-card [session]. The shopper
 * reference is typed into the session's form, as checkout reads it; nothing is stored until a charge starts.
 *
 * @param session The saved-card sale's own session.
 * @param progress The payment running or finished ([app.minimpos.app.payment.FinancialOperations.paymentProgress]),
 *   which the review screen follows to its result.
 * @param lookup Explicit lookup retained across the reference, cart and review screens.
 * @param settings The current settings, for the shopper reference source and recurring model.
 * @param currency The currency charged.
 * @param clock Stamps the generated merchant reference.
 * @param zone The time zone of that reference.
 */
class SavedCardViewModel(
    private val session: SaleSession,
    progress: Flow<TransactionState>,
    private val lookup: SavedCardLookup,
    settings: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val checkout = session.checkout(settings = settings, currency = currency)

    /** The screen state, updated as the form, cart, setup, lookup or charge changes. */
    val state: StateFlow<SavedCardUiState> =
        combine(checkout, lookup.state, progress) { current, found, charge ->
            val payment = current.payment
            SavedCardUiState(
                checkout = current,
                source = payment.shopperReferenceSource,
                blocked = found.blocked,
                finding = found.finding,
                cards = found.cards,
                failure = found.failure,
                searchedReference = found.reference,
                recurringModel = payment.recurringModel().value,
                charge = charge,
                lookup = found,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SavedCardUiState())

    /** Takes the typed customer reference or email, whichever the shopper reference is made from. */
    fun setReference(value: String) =
        session.updateForm {
            if (state.value.source == ShopperReferenceSource.EMAIL) it.copy(email = value) else it.copy(customerReference = value)
        }

    /** Lists the cards saved for the typed shopper reference; ignored while one is running, blocked or invalid. */
    fun find() = lookup.find()

    /**
     * Charges [card] for the rung-up sale; returns whether it started (false while blocked, nothing is rung up, the
     * card cannot be charged with the recurring model, or a charge is already running).
     */
    fun charge(card: StoredCard): Boolean {
        val current = state.value
        if (current.blocked != null) return false
        val start = session.savedCardStart(current.checkout, card, clock.instant(), zone()) ?: return false
        return lookup.start(start) != null
    }
}
