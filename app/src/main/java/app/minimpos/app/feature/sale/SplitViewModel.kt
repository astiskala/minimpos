package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.TenderBalance
import app.minimpos.app.data.repo.remainingAmountKnown
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.feature.giftcard.CardChoice
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.history.StoredPayments
import app.minimpos.app.payment.CashTenders
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.MealVoucherAvailability
import app.minimpos.app.payment.MealVoucherReading
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.SaleBook
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.splitStart
import app.minimpos.core.money.AmountEntry
import app.minimpos.core.split.TenderSplit
import app.minimpos.core.voucher.MealVoucherScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** How the operator chooses what the next tender of a split pays. */
enum class SplitMode {
    /** An amount typed in, up to the unpaid balance. */
    AMOUNT,

    /** One of a number of equal shares of the balance. */
    SHARES,

    /** Chosen units of the sold items, at their sold gross. */
    ITEMS,
}

/** The single tender-entry step shown after choosing a split amount. */
enum class SplitStage {
    /** Choose the amount and how to pay it. */
    CHOOSE,

    /** Enter cash received and confirm change. */
    CASH,

    /** Present a gift card. */
    GIFT_CARD,

    /** Review a meal voucher and its eligible amount. */
    VOUCHER,
}

/**
 * One sold line offered for item allocation.
 *
 * @property position The line's position in the sale, which allocations name.
 * @property name Item name as sold.
 * @property quantity Units sold.
 * @property allocated Units already paid or being paid by earlier tenders.
 */
data class SplitItem(
    val position: Int,
    val name: String,
    val quantity: Int,
    val allocated: Int,
)

/**
 * What the split screen shows.
 *
 * @property loaded Whether the sale or checkout has been read.
 * @property currency ISO 4217 code of the sale.
 * @property saleId The stored sale being collected, or null before the first tender of a new sale.
 * @property balance How the frozen total is divided so far; null until loaded or when no balance can be collected.
 * @property items Sold lines for item allocation.
 * @property mode How the next tender is chosen.
 * @property amount The amount typed in, for [SplitMode.AMOUNT].
 * @property shares How many equal shares a split begun now divides the balance into.
 * @property selection Units chosen per line position, for [SplitMode.ITEMS].
 * @property stage The tender-entry step currently shown.
 * @property received Cash received, entered on the keypad during [cashStage].
 * @property rejected Whether the last start was refused because the sale changed; review and try again.
 * @property recording Whether a cash tender is being recorded.
 * @property giftCards Configured gift-card providers that take payments; empty offers no gift-card tender.
 * @property card The gift card being presented.
 * @property voucher What a meal-voucher tender may do now; null when vouchers are not configured.
 * @property voucherAvailability Settings and verified discovery behind [voucher].
 * @property voucherChoice The market chosen for an offline demo; null uses the only one.
 */
data class SplitUiState(
    val loaded: Boolean = false,
    val currency: String = "",
    val saleId: String? = null,
    val balance: TenderBalance? = null,
    val items: List<SplitItem> = emptyList(),
    val mode: SplitMode = SplitMode.AMOUNT,
    val amount: AmountEntry = AmountEntry(),
    val shares: Int = 2,
    val selection: Map<Int, Int> = emptyMap(),
    val stage: SplitStage = SplitStage.CHOOSE,
    val received: AmountEntry = AmountEntry(),
    val rejected: Boolean = false,
    val recording: Boolean = false,
    val giftCards: List<GiftCardSetting> = emptyList(),
    val card: CardChoice = CardChoice(),
    val voucher: MealVoucherReading? = null,
    val voucherAvailability: MealVoucherAvailability = MealVoucherAvailability(),
    val voucherChoice: MealVoucherScheme? = null,
) {
    /** Whether the cash received is being entered for this tender. */
    val cashStage: Boolean get() = stage == SplitStage.CASH

    /** Whether the gift card for this tender is being presented. */
    val giftCardStage: Boolean get() = stage == SplitStage.GIFT_CARD

    /** Whether the next tender is being paid from a meal voucher. */
    val voucherStage: Boolean get() = stage == SplitStage.VOUCHER

    /** Whether shares of a split already begun are fixed, so their count can no longer change. */
    val sharesFixed: Boolean get() = balance?.nextShare != null

    /** How the next tender's amount is allocated: a share or items, or null for an amount. */
    val allocation: TenderAllocation?
        get() {
            val balance = balance ?: return null
            return when (mode) {
                SplitMode.AMOUNT -> null
                SplitMode.SHARES -> balance.nextShare ?: TenderAllocation.Share(0, shares, balance.unpaidMinor)
                SplitMode.ITEMS -> balance.itemsFor(selection)
            }
        }

    /** What the next tender collects, in minor units. */
    val amountMinor: Long get() = balance?.amountFor(allocation, amount.minor) ?: 0

    /** The equal shares of the split shown, largest first; empty outside [SplitMode.SHARES]. */
    val shareAmounts: List<Long>
        get() {
            val share = allocation as? TenderAllocation.Share ?: return emptyList()
            return TenderSplit.equalShares(share.baseMinor, share.count)
        }

    /** The share the next tender collects, zero-based; null outside [SplitMode.SHARES]. */
    val shareIndex: Int? get() = (allocation as? TenderAllocation.Share)?.index

    /** Whether the next tender can be collected as chosen. */
    val valid: Boolean get() = balance?.accepts(amountMinor, allocation) == true && (mode != SplitMode.ITEMS || allocation != null)

    /** Whether the gift card presented can pay this tender now. */
    val giftCardReady: Boolean get() = giftCardStage && valid && card.present(giftCards) != null

    /** Whether the meal voucher can pay the typed amount now: usable, and within what it may pay. */
    val voucherReady: Boolean get() =
        voucherStage && valid && mode == SplitMode.AMOUNT && voucher?.blocked == null && amountMinor in 1..(voucher?.limitMinor ?: 0)

    /** Change to hand back for the cash received; null while too little was entered. */
    val changeMinor: Long? get() = (received.minor - amountMinor).takeIf { received.minor >= amountMinor && amountMinor > 0 }

    /** Whether the cash entered can be confirmed now: enough was received and nothing is being recorded. */
    val confirmable: Boolean get() = cashStage && changeMinor != null && valid && !recording
}

/**
 * Splitting an immediate-charge sale across tenders: the first tender of the checkout being rung up ([saleId] null),
 * or the next tender of stored partially paid sale [saleId]. The operator chooses an amount, an equal share or sold
 * units; a card tender starts on the terminal like any payment, and cash is recorded locally once the cash received
 * and change are confirmed. Pure allocation rules are [TenderBalance]; every start is checked again where it is stored.
 *
 * @param saleId The stored sale to collect, or null for the session's checkout.
 * @param session The sale being rung up, for a new sale's first tender.
 * @param checkout The session's current checkout; ignored for a stored sale.
 * @param payments Stored sales and their tenders.
 * @param lifecycle Runs card tenders on the terminal.
 * @param cash Records cash tenders.
 * @param giftCards Configured gift-card providers that take payments.
 * @param vouchers Meal-voucher availability for a currency, from settings and verified store discovery.
 * @param clock Stamps references and cash tenders, and dates voucher use in [zone].
 * @param zone The time zone of generated references.
 */
class SplitViewModel(
    private val saleId: String?,
    private val session: SaleSession,
    checkout: Flow<Checkout>,
    payments: StoredPayments,
    private val lifecycle: TransactionLifecycle<PaymentStart>,
    private val cash: CashTenders,
    giftCards: Flow<List<GiftCardSetting>> = flowOf(emptyList()),
    vouchers: Flow<(String) -> MealVoucherAvailability> = flowOf { MealVoucherAvailability() },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val local = MutableStateFlow(SplitUiState())
    private var latestCheckout: Checkout? = null
    private var latestSale: SaleEntity? = null

    private val source: Flow<SplitUiState> =
        if (saleId == null) {
            checkout.map { current ->
                latestCheckout = current
                SplitUiState(
                    loaded = true,
                    currency = current.currency.code,
                    balance = SaleBook.splitBalance(current.totals, current.preAuthorisation).takeIf { current.canSplit },
                    items =
                        current.totals.lines.mapIndexed {
                            index,
                            priced,
                            ->
                            SplitItem(index, priced.line.name, priced.line.quantity, 0)
                        },
                )
            }
        } else {
            payments.observe(saleId).map { payment ->
                val record = payment?.record
                latestSale = record?.sale
                val balance =
                    record?.takeIf { it.sale.isPartiallyPaid && it.sale.remainingAmountKnown }?.let {
                        TenderBalance.of(it.sale, it.lines, it.tenders)
                    }
                SplitUiState(
                    loaded = record != null,
                    currency = record?.sale?.currency.orEmpty(),
                    saleId = saleId,
                    balance = balance,
                    items =
                        record?.sortedLines.orEmpty().map { line ->
                            val allocated = balance?.lines?.firstOrNull { it.lineId == line.position.toLong() }?.allocatedQuantity
                            SplitItem(line.position, line.name, line.quantity, allocated ?: 0)
                        },
                )
            }
        }

    /** The screen state, updated whenever the sale or checkout and the operator's choices change. */
    val state: StateFlow<SplitUiState> =
        combine(source, local, giftCards, vouchers) { loaded, choices, settings, voucherFor ->
            val provider =
                choices.card.provider?.takeIf { chosen -> settings.any { it.provider == chosen } } ?: settings.firstOrNull()?.provider
            val availability = voucherFor(loaded.currency)
            val voucher =
                loaded.balance?.takeIf { availability.configured }?.let { balance ->
                    Checkout.mealVoucher(
                        availability,
                        choices.voucherChoice,
                        balance,
                        today(),
                        latestSale?.context,
                    )
                }
            choices.copy(
                giftCards = settings,
                card = choices.card.forProvider(provider, settings),
                loaded = loaded.loaded,
                currency = loaded.currency,
                saleId = loaded.saleId,
                balance = loaded.balance,
                items = loaded.items,
                voucher = voucher,
                voucherAvailability = availability,
                stage = if (choices.voucherStage && voucher == null) SplitStage.CHOOSE else choices.stage,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SplitUiState())

    /** Chooses how the next tender is allocated. */
    fun selectMode(mode: SplitMode) = local.update { it.copy(mode = mode, stage = SplitStage.CHOOSE, rejected = false) }

    /** Updates the typed amount with a keypad step; the cash received while entering cash. */
    fun updateAmount(transform: (AmountEntry) -> AmountEntry) =
        local.update {
            if (it.cashStage) {
                it.copy(
                    received = transform(it.received),
                )
            } else {
                it.copy(amount = transform(it.amount), rejected = false)
            }
        }

    /** Sets the number of equal shares of a split begun now, within 2..[TenderSplit.MAX_SHARES]. */
    fun setShares(count: Int) = local.update { it.copy(shares = count.coerceIn(2, TenderSplit.MAX_SHARES), rejected = false) }

    /** Sets the units of line [position] this tender pays for, capped at what is unallocated. */
    fun setQuantity(
        position: Int,
        quantity: Int,
    ) {
        val line = state.value.items.firstOrNull { it.position == position } ?: return
        val capped = quantity.coerceIn(0, line.quantity - line.allocated)
        local.update { it.copy(selection = it.selection + (position to capped), rejected = false) }
    }

    /**
     * Starts the chosen tender on the terminal: a card, or, while the voucher stage is open, the typed amount from the
     * meal voucher ([Checkout.mealVoucherStart]); false when it is not ready now or a payment is running.
     */
    fun payByCard(): Boolean {
        val current = state.value
        if (current.voucherStage) {
            val reading = current.voucher ?: return false
            if (!current.voucherReady) return false
            val start =
                Checkout.mealVoucherStart(start(current), reading, current.voucherAvailability, current.amountMinor) ?: return reject()
            local.update { it.copy(stage = SplitStage.CHOOSE) }
            return lifecycle.start(start) != null
        }
        if (!current.valid) return false
        val start = start(current) ?: return reject()
        return lifecycle.start(start) != null
    }

    /** Opens or closes gift-card presentation for the chosen tender. */
    fun enterGiftCard(open: Boolean) =
        local.update {
            it.copy(
                stage = if (open && state.value.valid) SplitStage.GIFT_CARD else SplitStage.CHOOSE,
                card = it.card.copy(scanned = null),
                rejected = false,
            )
        }

    /** Updates the gift card being presented. */
    fun updateCard(transform: (CardChoice) -> CardChoice) = local.update { it.copy(card = transform(it.card)) }

    /** Starts the chosen tender as a gift-card redemption on the terminal; false when not ready or a payment is running. */
    fun payByGiftCard(): Boolean {
        val current = state.value
        if (!current.giftCardReady) return false
        val card = current.card.present(current.giftCards) ?: return false
        val start = start(current)?.withGiftCard(card) ?: return reject().also { card.discard() }
        local.update { it.copy(stage = SplitStage.CHOOSE, card = it.card.copy(scanned = null)) }
        return lifecycle.start(start) != null ||
            run {
                card.discard()
                false
            }
    }

    /**
     * Opens or closes paying the next tender from a meal voucher of market [choice] (an offline demo's pick; the only
     * market otherwise), as an amount starting at the most that market's voucher may pay; the operator may lower it,
     * for example to a short balance.
     */
    fun enterVoucher(
        open: Boolean,
        choice: MealVoucherScheme? = state.value.voucherChoice,
    ) {
        val current = state.value
        val limit =
            current.balance
                ?.takeIf { open && current.voucher != null }
                ?.let { Checkout.mealVoucher(current.voucherAvailability, choice, it, today(), latestSale?.context).limitMinor } ?: 0
        local.update {
            it.copy(
                stage = if (open && current.voucher != null) SplitStage.VOUCHER else SplitStage.CHOOSE,
                voucherChoice = choice,
                mode = if (open) SplitMode.AMOUNT else it.mode,
                amount = if (limit > 0) AmountEntry(limit) else it.amount,
                rejected = false,
            )
        }
    }

    /** Opens or closes cash entry for the chosen tender, starting with the exact amount received. */
    fun enterCash(open: Boolean) =
        local.update {
            it.copy(
                stage = if (open && state.value.valid) SplitStage.CASH else SplitStage.CHOOSE,
                received = AmountEntry(state.value.amountMinor),
                rejected = false,
            )
        }

    /**
     * Records the chosen tender as cash once the operator confirmed the cash received and the change handed back, then
     * calls [then] with the sale's ID; a refused recording (the sale changed meanwhile) asks for a new review instead.
     */
    fun confirmCash(then: (String) -> Unit) {
        val current = state.value
        if (!current.confirmable) return
        local.update { it.copy(recording = true) }
        launchWrite({
            val existing = latestSale
            if (saleId == null) {
                start(current)?.let { cash.payNew(it, current.received.minor, clock.millis()) }
            } else if (existing != null &&
                cash.payExisting(saleId, current.amountMinor, current.allocation, current.received.minor, existing.paidMinor)
            ) {
                saleId
            } else {
                null
            }
        }) { id ->
            local.update { SplitUiState(rejected = id == null) }
            id?.let(then)
        }
    }

    private fun start(current: SplitUiState): PaymentStart? {
        val sale = latestSale
        return if (saleId == null) {
            latestCheckout?.let { session.splitStart(it, clock.instant(), zone(), current.amountMinor, current.allocation) }
        } else {
            sale?.let { Checkout.remainingPayment(it, current.amountMinor, current.allocation) }
        }
    }

    private fun reject(): Boolean {
        local.update { it.copy(rejected = true) }
        return false
    }

    // Voucher closed days follow the store's local date.
    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))
}
