package app.minimpos.app.feature.refund

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.refund.RefundChoice
import app.minimpos.app.refund.RefundStart
import app.minimpos.app.refund.Refundability
import app.minimpos.app.refund.RefundablePayment
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.money.AmountEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What to refund, as offered on the refund screen. */
enum class RefundOption {
    /** Everything that is left: a full reversal when nothing was refunded before, else the remaining amount. */
    FULL,

    /** Chosen items of a sale taken on this terminal; each line's share of the total is refunded. */
    ITEMS,

    /** An amount typed in, up to what is left. */
    AMOUNT,
}

/**
 * What the refund screen shows.
 *
 * @property load Whether the payment can be refunded, once it has been looked up; null while it is being read.
 * @property option What to refund.
 * @property selection Units to refund per sale line ID, for [RefundOption.ITEMS].
 * @property amount The amount typed in, for [RefundOption.AMOUNT].
 * @property remoteReviewed Explicit staff review of an independently verified foreign receipt.
 */
data class RefundUiState(
    val load: Refundability? = null,
    val option: RefundOption = RefundOption.FULL,
    val selection: Map<Long, Int> = emptyMap(),
    val amount: AmountEntry = AmountEntry(),
    /** Staff independently checked the payment, remaining amount and destination when no local sale is known. */
    val remoteReviewed: Boolean = false,
) {
    /** The payment, when it can be refunded. */
    val original: RefundablePayment? get() = (load as? Refundability.Refundable)?.payment

    /** The chosen [option] with its details. */
    val choice: RefundChoice
        get() =
            when (option) {
                RefundOption.FULL -> RefundChoice.Everything
                RefundOption.ITEMS -> RefundChoice.Items(selection)
                RefundOption.AMOUNT -> RefundChoice.Amount(amount.minor)
            }

    /** The amount the chosen [option] refunds, in minor units; 0 before the payment is known. */
    val refundMinor: Long get() = original?.amountFor(choice) ?: 0

    /** Whether [refundMinor] can be refunded: more than 0 and no more than what is left. */
    val amountValid: Boolean get() = original?.isValid(choice) == true && (original?.local != null || remoteReviewed)
}

/**
 * Choosing what to refund of one payment, found from a sale in history or a scanned receipt QR code. A scanned code
 * of a sale taken on this terminal is linked to that sale, so its items and earlier refunds are known. Whether and how
 * much can be refunded is decided by [RefundablePayment].
 */
class RefundViewModel(
    /** The scanned refund QR code, or null when refunding [saleId]. */
    private val payload: String?,
    /** The sale to refund from history, or null when scanning. */
    private val saleId: String?,
    private val sales: SaleRepository,
    private val operations: StoredPaymentActions,
) : ViewModel() {
    private val _state = MutableStateFlow(RefundUiState())

    /** The screen state. */
    val state: StateFlow<RefundUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val qr = payload?.let(RefundQrPayload::decode)
            val record = saleId?.let { sales.get(it) } ?: qr?.let { sales.findByTransactionId(it.transactionId) }
            val load = RefundablePayment.find(record, qr)
            _state.update { it.copy(load = load) }
        }
    }

    /** Records explicit staff review of a receipt without local payment context. */
    fun reviewRemote(reviewed: Boolean) = _state.update { it.copy(remoteReviewed = reviewed) }

    /** Chooses what to refund. */
    fun selectOption(option: RefundOption) = _state.update { it.copy(option = option) }

    /** Sets the units of line [lineId] to refund, capped at what is left of it; ignored for an unknown line. */
    fun setQuantity(
        lineId: Long,
        quantity: Int,
    ) {
        val capped = _state.value.original?.cappedQuantity(lineId, quantity) ?: return
        _state.update { it.copy(selection = it.selection + (lineId to capped)) }
    }

    /** Updates the typed amount with a keypad step such as [AmountEntry.append]. */
    fun updateAmount(transform: (AmountEntry) -> AmountEntry) = _state.update { it.copy(amount = transform(it.amount)) }

    /**
     * Starts the refund; returns false when the amount is invalid or a refund is already running. Its merchant
     * reference is generated with "R" after the configured prefix.
     */
    fun refund(): Boolean {
        val current = _state.value
        if (!current.amountValid) return false
        return current.original?.let { operations.refund(it, current.choice) } == true
    }
}

/**
 * What the refund result screen shows.
 *
 * @property refund The refund, or null until loaded.
 * @property transaction The receipt, printing and emailing it, and re-checking an unknown outcome.
 */
data class RefundResultUiState(
    val refund: RefundEntity? = null,
    val transaction: TransactionActionsState = TransactionActionsState(),
) {
    /** Whether the terminal accepted the refund (Adyen confirms it later). */
    val accepted: Boolean get() = refund?.status == RefundStatus.REQUESTED

    /** Whether the refund's outcome is unknown and can be checked again with the terminal. */
    val canRecheck: Boolean get() = refund?.status == RefundStatus.UNKNOWN && refund.serviceId != null
}

/**
 * A refund's outcome and receipt, right after the refund or later from history: the receipt, printing and emailing it,
 * and re-checking an unknown outcome ([transaction], which delivers the automatic receipt of a refund [justMade]).
 *
 * @param refundId The refund shown.
 * @param refunds Where it is stored.
 * @param receipts Prints and emails its receipt.
 * @param lifecycle The refunds' lifecycle, for status checks and leaving the result.
 * @param justMade False when opened from history: nothing is delivered automatically and leaving acknowledges nothing.
 */
class RefundResultViewModel(
    refundId: String,
    refunds: RefundRepository,
    receipts: ReceiptDelivery,
    private val lifecycle: TransactionLifecycle<RefundStart>,
    private val justMade: Boolean = true,
) : ViewModel() {
    /** The receipt, printing and emailing it, and re-checking an unknown outcome. */
    val transaction = TransactionActions.forRefund(viewModelScope, refundId, receipts, lifecycle, fresh = justMade)

    /** The screen state, updated whenever the refund, settings, printer or an action changes. */
    val state: StateFlow<RefundResultUiState> =
        combine(refunds.observe(refundId), transaction.state) { refund, actions -> RefundResultUiState(refund, actions) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RefundResultUiState())

    /** Leaves the result, so the next refund can start. */
    fun finish() {
        if (justMade) lifecycle.acknowledge()
    }
}
