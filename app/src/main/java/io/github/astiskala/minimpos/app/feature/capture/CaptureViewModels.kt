package io.github.astiskala.minimpos.app.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.CaptureStep
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.feature.toState
import io.github.astiskala.minimpos.app.payment.Captures
import io.github.astiskala.minimpos.app.refund.PaymentAction
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.StoredPayment
import io.github.astiskala.minimpos.app.refund.StoredPayments
import io.github.astiskala.minimpos.core.money.AmountEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the operator types on the tip screen: the tip, or the total the customer wrote. */
enum class TipInput {
    /** The tip itself. */
    TIP,

    /** The total with the tip; the tip is what it adds to the bill. */
    TOTAL,
}

/**
 * What the tip screen shows.
 *
 * @property payment The sale awaiting its tip, or null until loaded (or when it is gone).
 * @property input What is being typed.
 * @property entry The amount typed.
 * @property submission Sending the tip; done once it went through, so the screen can close.
 */
data class TipUiState(
    val payment: StoredPayment? = null,
    val input: TipInput = TipInput.TIP,
    val entry: AmountEntry = AmountEntry(),
    val submission: ActionState = ActionState(),
) {
    /** The sale, or null until loaded. */
    val sale: SaleEntity? get() = payment?.sale

    /** What can be done with the sale now; the tip can be entered with [PaymentAction.ENTER_TIP]. */
    val actions: Set<PaymentAction> get() = payment?.actions.orEmpty()

    /** The bill: what was pre-authorised, in minor units. */
    val billMinor: Long get() = sale?.totalMinor ?: 0

    /** The tip the entry makes; null for a total below the bill. */
    val tipMinor: Long? get() = if (input == TipInput.TIP) entry.minor else (entry.minor - billMinor).takeIf { it >= 0 }

    /** Whether the typed total is below the bill (and so not valid). */
    val belowBill: Boolean get() = input == TipInput.TOTAL && entry.minor > 0 && tipMinor == null

    /**
     * Whether the tip is high enough that the authorisation is raised before the capture
     * ([PaymentStanding.tipNeedsAdjustment]).
     */
    val needsAdjustment: Boolean
        get() = tipMinor?.let { PaymentStanding.tipNeedsAdjustment(billMinor, it) } == true

    /** Whether the sale still awaits a tip and nothing is being sent. */
    val canSubmit: Boolean get() = PaymentAction.ENTER_TIP in actions && !submission.running

    /** Whether "Add tip" is enabled: [canSubmit] with a tip above zero ("No tip" is its own button). */
    val canAddTip: Boolean get() = canSubmit && (tipMinor ?: 0) > 0
}

/**
 * Entering the tip written on the receipt of [saleId] and capturing the bill plus the tip (see [Captures.addTip]). The
 * tip is sent with `persisting`, so it finishes even if the screen closes.
 *
 * @param saleId The sale awaiting its tip.
 * @param payments Follows it.
 * @param captures Enters the tip and captures.
 */
class TipViewModel(
    private val saleId: String,
    payments: StoredPayments,
    private val captures: Captures,
) : ViewModel() {
    private val local = MutableStateFlow(TipUiState())

    /** The screen state, updated whenever the sale or the entry changes. */
    val state: StateFlow<TipUiState> =
        combine(payments.observe(saleId), local) { payment, ui -> ui.copy(payment = payment) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TipUiState())

    /** Switches between typing the tip and the total, starting the entry afresh. */
    fun setInput(input: TipInput) = local.update { it.copy(input = input, entry = AmountEntry()) }

    /** Updates the typed amount with a keypad step such as [AmountEntry.append]. */
    fun updateEntry(transform: (AmountEntry) -> AmountEntry) = local.update { it.copy(entry = transform(it.entry)) }

    /** Enters [tipMinor] (0 for no tip) and captures; ignored while one is being sent or the sale no longer awaits a tip. */
    fun submit(tipMinor: Long) {
        val current = state.value
        val sale = current.sale?.takeIf { current.canSubmit } ?: return
        local.update { it.copy(submission = ActionState(running = true)) }
        launchWrite({ captures.addTip(saleId, tipMinor) }) { result ->
            local.update { it.copy(submission = result.toState(CaptureStep.TIP, current.billMinor + tipMinor, sale.currency)) }
        }
    }
}

/**
 * What the capture screen shows.
 *
 * @property payment The pre-authorisation, or null until loaded (or when it is gone).
 * @property adjustOnly Adjust what it holds without capturing.
 * @property entry The amount typed; null until the first key, while the amount held is proposed.
 * @property submission Sending the capture or adjustment; done once it went through, so the screen can close.
 */
data class CaptureUiState(
    val payment: StoredPayment? = null,
    val adjustOnly: Boolean = false,
    val entry: AmountEntry? = null,
    val submission: ActionState = ActionState(),
) {
    /** The pre-authorisation's sale, or null until loaded. */
    val sale: SaleEntity? get() = payment?.sale

    /** What can be done with it now: [PaymentAction.CAPTURE] and [PaymentAction.ADJUST]. */
    val actions: Set<PaymentAction> get() = payment?.actions.orEmpty()

    /** What the pre-authorisation holds now, in minor units. */
    val heldMinor: Long get() = sale?.heldMinor ?: 0

    /** The amount to capture or hold: the typed one, else what is held. */
    val amountMinor: Long get() = entry?.minor ?: heldMinor

    /**
     * Whether it can be sent: the pre-authorisation can still be captured (or adjusted, with [adjustOnly]), an amount is
     * set and nothing is being sent.
     */
    val canSubmit: Boolean
        get() = (if (adjustOnly) PaymentAction.ADJUST else PaymentAction.CAPTURE) in actions && amountMinor > 0 && !submission.running
}

/**
 * Capturing the pre-authorisation [saleId] (adjusting it first when more is captured than it holds), or with
 * [adjustOnly] changing what it holds (see [Captures]). Sent with `persisting`, so it finishes even if the screen
 * closes.
 *
 * @param saleId The pre-authorisation.
 * @param adjustOnly Adjust without capturing.
 * @param payments Follows it.
 * @param captures Captures and adjusts.
 */
class CaptureViewModel(
    private val saleId: String,
    adjustOnly: Boolean,
    payments: StoredPayments,
    private val captures: Captures,
) : ViewModel() {
    private val local = MutableStateFlow(CaptureUiState(adjustOnly = adjustOnly))

    /** The screen state, updated whenever the pre-authorisation, the capture mode or the entry changes. */
    val state: StateFlow<CaptureUiState> =
        combine(payments.observe(saleId), local) { payment, ui -> ui.copy(payment = payment) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState(adjustOnly = adjustOnly))

    /** Updates the typed amount with a keypad step; the first key replaces the amount held that was proposed. */
    fun updateEntry(transform: (AmountEntry) -> AmountEntry) = local.update { it.copy(entry = transform(it.entry ?: AmountEntry())) }

    /** Captures (or adjusts to) the amount; ignored when it cannot be sent. */
    fun submit() {
        val current = state.value
        val sale = current.sale?.takeIf { current.canSubmit } ?: return
        val amount = current.amountMinor
        val step = if (current.adjustOnly) CaptureStep.ADJUSTMENT else CaptureStep.CAPTURE
        local.update { it.copy(submission = ActionState(running = true)) }
        launchWrite({ if (current.adjustOnly) captures.adjust(saleId, amount) else captures.capture(saleId, amount) }) { result ->
            local.update { it.copy(submission = result.toState(step, amount, sale.currency)) }
        }
    }
}
