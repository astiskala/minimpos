package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.SavedCardLookup
import app.minimpos.app.payment.SavedCardLookupState
import app.minimpos.app.payment.TokenDeletionOffer
import app.minimpos.app.payment.TokenDeletionState
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.payment.savedCardStart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.terminal.checkout.StoredCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.ZoneId

/**
 * What the saved-card screens show.
 *
 * @property checkout The saved-card session's checkout: its form holds the shopper reference typed, its cart the sale.
 * @property source What the shopper reference is made from, which decides the field asked for.
 * @property recurringModel Adyen's `recurringProcessingModel` charges use; cards not saved for it cannot be chosen.
 * @property charge The charge running or finished; [TransactionState.Idle] before one starts.
 * @property lookup The current lookup: why it is blocked, whether it is running, the cards found or why it failed, and
 *   which of them can be charged.
 * @property deletion Latest token-management outcome, retained across navigation.
 * @property lookupAcknowledged Whether the operator closed the popup reporting [lookup]'s answer on this screen.
 * @property deletionAcknowledged Whether the operator closed the popup reporting [deletion]'s outcome on this screen.
 */
data class SavedCardUiState(
    val checkout: Checkout = Checkout(),
    val source: ShopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE,
    val recurringModel: String = "",
    val charge: TransactionState = TransactionState.Idle,
    val lookup: SavedCardLookupState = SavedCardLookupState(),
    val deletion: TokenDeletionState = TokenDeletionState(),
    val lookupAcknowledged: Boolean = false,
    val deletionAcknowledged: Boolean = false,
) {
    /** The shopper reference the typed field makes, as checkout makes it; null while it is not valid. */
    val shopperReference: String? get() = checkout.shopperReference
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

    // The answers whose popups were closed; a new request clears its own, so an equal later answer is reported again.
    private val acknowledged = MutableStateFlow(Acknowledged())

    /** The screen state, updated as the form, cart, setup, lookup or charge changes. */
    val state: StateFlow<SavedCardUiState> =
        combine(checkout, lookup.state, progress, lookup.deletion, acknowledged) { current, found, charge, deletion, closed ->
            val payment = current.payment
            SavedCardUiState(
                checkout = current,
                source = payment.shopperReferenceSource,
                recurringModel = payment.recurringModel().value,
                charge = charge,
                lookup = found,
                deletion = deletion,
                lookupAcknowledged = closed.lookup == found,
                deletionAcknowledged = closed.deletion == deletion,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SavedCardUiState())

    /** Takes the typed customer reference or email, whichever the shopper reference is made from. */
    fun setReference(value: String) =
        session.updateForm {
            if (state.value.source == ShopperReferenceSource.EMAIL) it.copy(email = value) else it.copy(customerReference = value)
        }

    /** Lists the cards saved for the typed shopper reference; ignored while one is running, blocked or invalid. */
    fun find() {
        acknowledged.update { it.copy(lookup = null) }
        lookup.find()
    }

    /** Closes the popup reporting the [shown] lookup answer; a later answer is reported again. */
    fun acknowledgeLookup(shown: SavedCardLookupState) = acknowledged.update { it.copy(lookup = shown) }

    /** Freezes a listed token for the deletion confirmation, independently of charge eligibility. */
    fun removal(card: StoredCard): TokenDeletionOffer? = lookup.removal(card)

    /** Deletes only the token/context retained by the confirmed [offer]. */
    fun delete(offer: TokenDeletionOffer): Boolean {
        acknowledged.update { it.copy(deletion = null) }
        return lookup.delete(offer)
    }

    /** Fresh read-only reconciliation of a deletion whose outcome is unknown. */
    fun recheckDeletion() {
        acknowledged.update { it.copy(deletion = null) }
        lookup.recheckDeletion()
    }

    /** Closes the popup reporting the [shown] deletion outcome; an unconfirmed deletion stays explained on the screen. */
    fun acknowledgeDeletion(shown: TokenDeletionState) = acknowledged.update { it.copy(deletion = shown) }

    /**
     * Charges [card] for the rung-up sale; returns whether it started (false while blocked, nothing is rung up, the
     * card cannot be charged with the recurring model, or a charge is already running).
     */
    fun charge(card: StoredCard): Boolean {
        val current = state.value
        if (current.lookup.blocked != null) return false
        val start = session.savedCardStart(current.checkout, card, clock.instant(), zone()) ?: return false
        return lookup.start(start) != null
    }

    private data class Acknowledged(
        val lookup: SavedCardLookupState? = null,
        val deletion: TokenDeletionState? = null,
    )
}
