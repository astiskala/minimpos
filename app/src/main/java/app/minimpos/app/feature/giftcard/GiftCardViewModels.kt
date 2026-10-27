package app.minimpos.app.feature.giftcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.GiftCardAction
import app.minimpos.app.data.repo.GiftCardActivity
import app.minimpos.app.data.repo.GiftCardRepository
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.GiftCardBalanceResult
import app.minimpos.app.payment.GiftCardFunding
import app.minimpos.app.payment.GiftCardRequest
import app.minimpos.app.payment.GiftCards
import app.minimpos.app.payment.PresentedGiftCard
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.payment.giftCardStartProblem
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.money.AmountEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * What staff present for the next request: a configured provider, how its card is presented and, for a scanned card,
 * its transient number and expiry date. The number never leaves this process-only state except into the request.
 *
 * @property provider The chosen configured provider; null before providers are read or when none is configured.
 * @property entry How the card is presented; one of the provider's configured entries.
 * @property scanned A scanned number, held only until it is sent or abandoned.
 * @property expiry The scanned card's expiry date as typed (`MMYY`).
 */
data class CardChoice(
    val provider: GiftCardProvider? = null,
    val entry: GiftCardEntry = GiftCardEntry.SWIPED,
    val scanned: String? = null,
    val expiry: String = "",
) {
    override fun toString(): String =
        "CardChoice(provider=${provider?.name ?: "none"}, entry=$entry, scanned=${if (scanned == null) "no" else "[redacted]"})"

    /** These choices for [provider], keeping [entry] when [settings] allow it there, else its first configured one. */
    fun forProvider(
        provider: GiftCardProvider?,
        settings: List<GiftCardSetting>,
    ): CardChoice {
        val entries = settings.firstOrNull { it.provider == provider }?.entries.orEmpty()
        return copy(provider = provider, entry = entry.takeIf { it in entries } ?: entries.minOrNull() ?: entry)
    }

    /** The card these choices present with [settings], or null while incomplete. */
    fun present(settings: List<GiftCardSetting>): PresentedGiftCard? {
        val setting = settings.firstOrNull { it.provider == provider } ?: return null
        return PresentedGiftCard.of(setting, entry, scanned, expiry)
    }
}

/**
 * What the Gift cards screen shows.
 *
 * @property loaded Whether settings were read.
 * @property settings Configured providers.
 * @property currency The currency gift cards are activated and loaded in.
 * @property card The card being presented.
 * @property operation The chosen action.
 * @property amount The amount typed for activation, load, cash-out or refund.
 * @property funding How an activation or load is paid for.
 * @property externalConfirmed Whether staff confirmed receiving the funds outside the app.
 * @property balance The last balance read; null when none was read for the current card.
 * @property reading Whether a balance is being read.
 * @property managerConfigured Whether value changes are public or a Manager PIN is configured to approve them.
 * @property blocked Why gift-card requests cannot be sent on this destination now; null when they can.
 * @property activities Recent and unresolved activities, newest first.
 * @property now Epoch milliseconds the undo window is read against.
 */
data class GiftCardsUiState(
    val loaded: Boolean = false,
    val settings: List<GiftCardSetting> = emptyList(),
    val currency: String = PaymentSettings.FALLBACK_CURRENCY,
    val card: CardChoice = CardChoice(),
    val operation: GiftCardOperation = GiftCardOperation.BALANCE,
    val amount: AmountEntry = AmountEntry(),
    val funding: GiftCardFunding = GiftCardFunding.CARD,
    val externalConfirmed: Boolean = false,
    val balance: GiftCardBalanceResult? = null,
    val reading: Boolean = false,
    val managerConfigured: Boolean = false,
    val blocked: SetupProblem? = null,
    val activities: List<GiftCardActivity> = emptyList(),
    val now: Long = 0,
) {
    /** The configured setting of the chosen provider. */
    val setting: GiftCardSetting? get() = settings.firstOrNull { it.provider == card.provider }

    /** What the chosen provider supports, in the screen's order; balance first. */
    val operations: List<GiftCardOperation>
        get() =
            setting
                ?.provider
                ?.let { provider ->
                    OFFERED.filter { provider.supports(it) }
                }.orEmpty()

    /** Whether the chosen action changes a card's value and so needs the Manager PIN. */
    val changesValue: Boolean get() = operation != GiftCardOperation.BALANCE

    /** Whether the chosen action takes an amount. */
    val takesAmount: Boolean
        get() = operation in setOf(GiftCardOperation.ACTIVATE, GiftCardOperation.LOAD, GiftCardOperation.CASH_OUT, GiftCardOperation.REFUND)

    /** The balance read for the presented card in this screen's currency; null when none was read. */
    val readBalance: Long?
        get() = (balance as? GiftCardBalanceResult.Read)?.takeIf { it.currency == currency }?.balanceMinor

    /**
     * Whether a cash-out pays the whole balance just read, as Adyen documents for every provider but Givex; the amount
     * is then that balance rather than typed.
     */
    val wholeBalance: Boolean
        get() = operation == GiftCardOperation.CASH_OUT && setting?.provider?.partialCashOut == false && readBalance != null

    /** Whether the chosen action is funded: an activation with a value, or a load. */
    val funded: Boolean
        get() = (operation == GiftCardOperation.ACTIVATE && amount.minor > 0) || operation == GiftCardOperation.LOAD

    /** Why the chosen action cannot start: the destination, the provider or a missing Manager PIN; null when it can. */
    val reason: Failure?
        get() = giftCardStartProblem(operation, setting, blocked, managerConfigured)?.let(Failure::NotSetUp)

    /** Whether the chosen action can start now with what was entered. */
    val ready: Boolean
        get() =
            reason == null && card.present(settings) != null && !reading &&
                when {
                    operation == GiftCardOperation.BALANCE || operation == GiftCardOperation.DEACTIVATE -> true
                    operation == GiftCardOperation.ACTIVATE && amount.minor == 0L -> true
                    amount.minor <= 0 -> false
                    funded && funding == GiftCardFunding.EXTERNAL -> externalConfirmed
                    else -> true
                }

    /** Activities that still need a check, a confirmation or a repair. */
    val attention: List<GiftCardActivity> get() = activities.filter { it.needsAttention }

    /** Loads that may still be undone. */
    val undoable: List<GiftCardActivity> get() = activities.filter { GiftCardAction.UNDO_LOAD in it.actions(now, activities) }

    private companion object {
        val OFFERED =
            listOf(
                GiftCardOperation.BALANCE,
                GiftCardOperation.ACTIVATE,
                GiftCardOperation.LOAD,
                GiftCardOperation.CASH_OUT,
                GiftCardOperation.REFUND,
                GiftCardOperation.DEACTIVATE,
            )
    }
}

/**
 * The Gift cards screen: choosing a configured provider and how its card is presented, then reading its balance or
 * starting a value change, which the screen confirms and approves first. Starts go through [GiftCards], which stores
 * and sends; this keeps only choices and the last balance read.
 *
 * @param giftCards Sends gift-card requests.
 * @param records Recent activities.
 * @param payment Payment settings with the configured providers and the currency.
 * @param managerConfigured Whether the relevant action is public or a Manager PIN is configured to approve it.
 * @param blocked Why gift-card requests cannot be sent now, from the terminal state.
 * @param clock Reads the undo window.
 */
class GiftCardsViewModel(
    private val giftCards: GiftCards,
    records: GiftCardRepository,
    payment: Flow<Pair<PaymentSettings, String>>,
    managerConfigured: Flow<Boolean>,
    blocked: Flow<SetupProblem?>,
    clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val local = MutableStateFlow(GiftCardsUiState())

    /** The screen state. */
    val state: StateFlow<GiftCardsUiState> =
        combine(
            local,
            payment,
            managerConfigured,
            blocked,
            records.recent(clock.millis() - RECENT_MILLIS),
        ) { choices, settings, pin, problem, recent ->
            val (paymentSettings, currency) = settings
            val providers = paymentSettings.offeredGiftCards
            val provider =
                choices.card.provider?.takeIf { chosen -> providers.any { it.provider == chosen } } ?: providers.firstOrNull()?.provider
            choices.copy(
                loaded = true,
                settings = providers,
                currency = currency,
                card = choices.card.forProvider(provider, providers),
                managerConfigured = pin,
                blocked = problem,
                activities = recent,
                now = clock.millis(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GiftCardsUiState())

    /** Chooses [provider]; a different card's balance and scan are forgotten. */
    fun selectProvider(provider: GiftCardProvider) =
        local.update { it.copy(card = CardChoice(provider = provider), balance = null, externalConfirmed = false) }

    /** Chooses how the card is presented, forgetting a scanned number. */
    fun selectEntry(entry: GiftCardEntry) = local.update { it.copy(card = it.card.copy(entry = entry, scanned = null), balance = null) }

    /** Keeps a scanned [number] (digits only) for the next request. */
    fun scanned(number: String) = local.update { it.copy(card = it.card.copy(scanned = number.filter(Char::isDigit)), balance = null) }

    /** Sets the scanned card's expiry date, at most four digits. */
    fun setExpiry(value: String) = local.update { it.copy(card = it.card.copy(expiry = value.filter(Char::isDigit).take(EXPIRY_LENGTH))) }

    /** Chooses the action; a cash-out starts from the balance just read. */
    fun selectOperation(operation: GiftCardOperation) {
        val read = state.value.readBalance
        local.update {
            val amount = if (operation == GiftCardOperation.CASH_OUT && read != null) AmountEntry(read) else it.amount
            it.copy(operation = operation, externalConfirmed = false, amount = amount)
        }
    }

    /** Updates the typed amount with a keypad step. */
    fun updateAmount(transform: (AmountEntry) -> AmountEntry) =
        local.update { it.copy(amount = transform(it.amount), externalConfirmed = false) }

    /** Chooses how an activation or load is paid for. */
    fun selectFunding(funding: GiftCardFunding) = local.update { it.copy(funding = funding, externalConfirmed = false) }

    /** Confirms or withdraws that the funds were received outside the app. */
    fun confirmExternal(confirmed: Boolean) = local.update { it.copy(externalConfirmed = confirmed) }

    /** Reads the presented card's balance. */
    fun readBalance() {
        val current = state.value
        val card = current.card.present(current.settings) ?: return
        local.update { it.copy(reading = true, balance = null) }
        viewModelScope.launch {
            val result = giftCards.balance(card)
            // A scanned number is never kept beyond one request: a cash-out that follows scans the card again.
            local.update { it.copy(reading = false, balance = result, card = it.card.copy(scanned = null)) }
        }
    }

    /**
     * Starts the chosen value change after confirmation and approval; returns its activity's group ID, or null when it
     * cannot start (another operation is running, or the choices changed).
     */
    fun start(): String? {
        val current = state.value
        if (!current.ready || !current.changesValue) return null
        val card = current.card.present(current.settings) ?: return null
        val amount = current.amount.minor
        val request =
            when (current.operation) {
                GiftCardOperation.ACTIVATE -> {
                    GiftCardRequest.Activate(
                        card,
                        amount,
                        current.currency,
                        current.funding.takeIf { amount > 0 },
                    )
                }

                GiftCardOperation.LOAD -> {
                    GiftCardRequest.Load(card, amount, current.currency, current.funding)
                }

                GiftCardOperation.CASH_OUT -> {
                    GiftCardRequest.CashOut(card, amount, current.currency)
                }

                GiftCardOperation.REFUND -> {
                    GiftCardRequest.Refund(card, amount, current.currency)
                }

                GiftCardOperation.DEACTIVATE -> {
                    GiftCardRequest.Deactivate(card, current.currency)
                }

                GiftCardOperation.BALANCE, GiftCardOperation.UNDO_LOAD, GiftCardOperation.REDEEM -> {
                    return null
                }
            }
        val started = giftCards.start(request)
        if (started == null) card.discard()
        local.update { it.copy(card = it.card.copy(scanned = null), amount = AmountEntry(), externalConfirmed = false, balance = null) }
        return started
    }

    /** Recent activity, and how long a card number may be. */
    private companion object {
        const val RECENT_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val EXPIRY_LENGTH = 4
    }
}

/**
 * What a gift-card activity screen shows: its progress while sent, then its stages, actions and receipt.
 *
 * @property activity The stored activity; null before it is stored or once it is gone.
 * @property progress The runner's progress when it is this activity.
 * @property actions What staff may do now.
 * @property transaction Receipt and status check actions.
 * @property settings Configured providers, for presenting the card again.
 * @property managerConfigured Whether the relevant action is public or a Manager PIN is configured to approve it.
 * @property refused Whether the last follow-up could not start or was not recorded.
 * @property card The card presented again to send a funded value again.
 */
data class GiftCardActivityUiState(
    val activity: GiftCardActivity? = null,
    val progress: TransactionState = TransactionState.Idle,
    val actions: Set<GiftCardAction> = emptySet(),
    val transaction: TransactionActionsState = TransactionActionsState(),
    val settings: List<GiftCardSetting> = emptyList(),
    val managerConfigured: Boolean = false,
    val refused: Boolean = false,
    val card: CardChoice = CardChoice(),
) {
    /** Whether the card presented again is complete. */
    val cardReady: Boolean get() = card.present(settings) != null

    /** Whether [groupId]'s terminal requests are being sent now. */
    fun processing(groupId: String): Boolean = (progress as? TransactionState.Processing)?.id == groupId
}

/**
 * One stored-value activity: following it while sent, then its stages, receipt, status check and the repairs or
 * confirmations it still needs.
 *
 * @param groupId The activity.
 * @param giftCards Sends follow-ups.
 * @param records Stored activities.
 * @param receipts Receipt delivery.
 * @param operations Status checks.
 * @param payment Configured providers.
 * @param managerConfigured Whether the relevant action is public or a Manager PIN is configured to approve it.
 * @param justStarted Whether it was just started here, so leaving acknowledges its progress.
 * @param clock Reads the undo window.
 */
class GiftCardActivityViewModel(
    private val groupId: String,
    private val giftCards: GiftCards,
    records: GiftCardRepository,
    receipts: ReceiptDelivery,
    operations: FinancialOperations,
    payment: Flow<PaymentSettings>,
    managerConfigured: Flow<Boolean>,
    private val justStarted: Boolean,
    clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    /** The receipt, printing and emailing it, and checking an unknown stage again. */
    val transaction = TransactionActions.forGiftCard(viewModelScope, groupId, receipts, operations)

    private val refused = MutableStateFlow(false)
    private val card = MutableStateFlow(CardChoice())
    private val recent = records.recent(clock.millis() - UNDO_LOOKBACK_MILLIS)

    /** The screen state. */
    val state: StateFlow<GiftCardActivityUiState> =
        combine(
            combine(records.observe(groupId), recent, ::Pair),
            giftCards.state,
            transaction.state,
            combine(payment, managerConfigured, ::Pair),
            combine(refused, card, ::Pair),
        ) { (activity, others), progress, actions, (settings, pin), (wasRefused, presented) ->
            GiftCardActivityUiState(
                activity = activity,
                progress = progress,
                actions = activity?.actions(clock.millis(), others).orEmpty(),
                transaction = actions,
                settings = settings.offeredGiftCards,
                managerConfigured = pin,
                refused = wasRefused,
                card = presented.forProvider(activity?.provider, settings.offeredGiftCards),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GiftCardActivityUiState())

    /** Asks the terminal to stop the running request. */
    fun cancel() = giftCards.cancel()

    /** Updates the card presented again. */
    fun updateCard(transform: (CardChoice) -> CardChoice) = card.update(transform)

    /** Runs the confirmed and approved follow-up [action]. */
    fun follow(action: GiftCardAction) {
        when (action) {
            GiftCardAction.CONTINUE -> continueValue()
            GiftCardAction.REFUND_FUNDING -> refundFunding()
            GiftCardAction.RETURN_FUNDS -> returnFunds()
            GiftCardAction.CONFIRM_HANDOVER -> confirmHandover()
            GiftCardAction.UNDO_LOAD -> undo()
            GiftCardAction.CHECK_AGAIN -> transaction.recheck()
        }
        card.update { it.copy(scanned = null) }
    }

    /** Sends the funded value again with the card presented again. */
    private fun continueValue() {
        val current = state.value
        val presented = current.card.present(current.settings) ?: return
        val activity = current.activity
        val funding = activity?.funding ?: return presented.discard()
        follow(GiftCardRequest.Continue(groupId, presented, funding.movedMinor, activity.currency)) { presented.discard() }
    }

    /** Undoes this activity's load. */
    private fun undo() {
        val load = state.value.activity?.value ?: return
        follow(GiftCardRequest.UndoLoad(groupId, load.movedMinor, load.currency))
    }

    /** Refunds the funding card payment. */
    private fun refundFunding() {
        val funding = state.value.activity?.funding ?: return
        follow(GiftCardRequest.RefundFunding(groupId, funding.movedMinor, funding.currency))
    }

    /** Records that externally received funds were given back. */
    private fun returnFunds() {
        val funding = state.value.activity?.funding ?: return
        follow(GiftCardRequest.ReturnFunds(groupId, funding.movedMinor, funding.currency))
    }

    /** Records that the cash-out's cash was handed over. */
    private fun confirmHandover() {
        refused.value = false
        viewModelScope.launch { refused.value = !giftCards.confirmHandover(groupId) }
    }

    /** Leaves the result, so the next activity can start. */
    fun finish() {
        if (justStarted) giftCards.acknowledge()
    }

    private fun follow(
        request: GiftCardRequest,
        onRefused: () -> Unit = {},
    ) {
        giftCards.acknowledge()
        val started = giftCards.start(request)
        refused.value = started == null
        if (started == null) onRefused()
    }

    private companion object {
        const val UNDO_LOOKBACK_MILLIS = 2L * 24 * 60 * 60 * 1000
    }
}
