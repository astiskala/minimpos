package app.minimpos.app.payment

import android.database.SQLException
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.history.simulatedSale
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

/**
 * A saved-card charge rung up in its own session: what checkout made of the cart, and the card chosen for it.
 *
 * @property payment The priced cart, references and shopper reference; an immediate-charge sale with a shopper reference.
 * @property card The saved card to charge.
 * @property recurringModel Adyen's `recurringProcessingModel` to charge it with, frozen with the sale.
 * @throws IllegalArgumentException for a payment that is not an immediate-charge sale with a shopper reference.
 */
data class SavedCardStart(
    val payment: PaymentStart,
    val card: StoredCard,
    val recurringModel: String,
) {
    init {
        require(payment.kind == SaleKind.SALE && !payment.manualCapture) { "A saved card is charged straight away" }
        require(payment.shopperReference != null) { "A saved card is charged for a shopper reference" }
    }
}

/** The cards saved for a shopper reference, or why they could not be listed. */
sealed interface CardLookup {
    /**
     * The cards Adyen listed (simulated ones while payments go to the simulator).
     *
     * @property cards The cards, in Adyen's order; empty when the shopper has none.
     */
    data class Listed(
        val cards: List<StoredCard>,
    ) : CardLookup

    /**
     * Nothing could be listed; listing changes nothing, so it can be asked again.
     *
     * @property failure Why: the Checkout API is not set up, or Adyen refused or did not answer.
     */
    data class NotDone(
        val failure: Failure,
    ) : CardLookup
}

/**
 * Sales paid with a saved card through Adyen's Checkout API, with the shopper not present: listing a shopper's cards
 * ([cards]), charging one ([start]) and asking again about a charge whose outcome is unknown ([recheck]).
 *
 * Each charge is stored as a PENDING sale, with the card and recurring model it charges, before Adyen is called, and is
 * sent from the stored sale with the idempotency key [idempotencyKey], so asking again sends the same request and
 * Adyen answers what it did the first time instead of charging twice. Adyen keeps idempotency keys for at least
 * [RECHECK_WINDOW]; an older unknown charge is not sent again and is checked in the Customer Area. Manager approval is
 * checked again immediately before anything is sent. Charges and checks run one at a time.
 *
 * @param scope Where charges run, so one survives leaving the screen.
 * @param sales Where the sales are stored.
 * @param target Where saved cards go now: [app.minimpos.app.terminal.AdyenApi.target] in the app.
 * @param onCharged Called once with the sale ID and its original start when its charge is approved in this process,
 *   to clear its originating session and arm automatic delivery.
 * @param clock Stamps the sales and decides whether an unknown charge may be sent again.
 * @param newId Generates sale IDs.
 * @param permits Whether Manager approval allows a charge now, checked under the operation lock.
 */
class SavedCardCharges(
    private val scope: CoroutineScope,
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    private val onCharged: (id: String, start: SavedCardStart) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
    private val permits: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val unresolved = mutableMapOf<String, SavedCardStart>()

    /** The charge running now, which the charging screen follows to its result. */
    val state: StateFlow<TransactionState>
        field = MutableStateFlow<TransactionState>(TransactionState.Idle)

    /** Lists the cards saved under [shopperReference] where payments go now; nothing is stored. */
    suspend fun cards(shopperReference: String): CardLookup {
        val current = target()
        return when (val access = current.savedCards(current.context)) {
            is ApiAccess.Blocked -> {
                CardLookup.NotDone(Failure.NotSetUp(access.problem))
            }

            is ApiAccess.Ready -> {
                when (val listed = access.client.list(shopperReference)) {
                    is StoredCardListing.Listed -> CardLookup.Listed(listed.cards)
                    is StoredCardListing.Failed -> CardLookup.NotDone(Failure.Remote(listed.fault))
                }
            }
        }
    }

    /**
     * Charges [start] in the background and returns the new sale's ID straight away; null, with nothing stored or sent,
     * while another charge is running (such as a second tap on the same action).
     */
    @Synchronized
    fun start(start: SavedCardStart): String? {
        if (state.value is TransactionState.Processing) return null
        val id = newId()
        state.value = TransactionState.Processing(id)
        synchronized(unresolved) { unresolved[id] = start }
        scope.launch {
            try {
                val opened = SaleBook.savedCardSale(id, start, clock.millis(), target().context)
                sales.createPending(opened, SaleBook.lines(id, start.payment.totals))
                mutex.withLock { sales.get(id)?.sale?.let { send(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: SQLException) {
                interrupted(id)
            } catch (ignored: IllegalStateException) {
                interrupted(id)
            } finally {
                state.value = TransactionState.Finished(id)
            }
        }
        return id
    }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next charge can start. */
    fun acknowledge() {
        if (state.value is TransactionState.Finished) state.value = TransactionState.Idle
    }

    /**
     * Sends the charge of sale [saleId], whose outcome is unknown, again with its stored request and idempotency key, and
     * stores Adyen's answer. Anything else has nothing left to check ([Recheck.Settled]); nothing changes when Manager
     * approval, the Checkout API or the original payment context is missing, or the charge is older than
     * [RECHECK_WINDOW].
     */
    suspend fun recheck(saleId: String): Recheck =
        mutex.withLock {
            val sale =
                sales.get(saleId)?.sale?.takeIf { it.savedCard && it.status == SaleStatus.UNKNOWN } ?: return@withLock Recheck.Settled
            if (Duration.ofMillis(clock.millis() - sale.createdAt) > RECHECK_WINDOW) {
                return@withLock Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD))
            }
            send(sale)?.let { Recheck.StillUnknown(it) } ?: Recheck.Settled
        }

    /** Sends the stored charge [sale] and stores the answer; returns why nothing (or nothing certain) is known yet. */
    private suspend fun send(sale: SaleEntity): Failure? {
        val pending = sale.status == SaleStatus.PENDING
        if (!permits()) {
            if (pending) sales.record(sale.id, SaleEvent.NotSetUp(SetupProblem.MANAGER_APPROVAL))
            return Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)
        }
        val client: SavedCardApi =
            when (val access = target().savedCards(sale.context)) {
                is ApiAccess.Ready -> {
                    access.client
                }

                is ApiAccess.Blocked -> {
                    // Nothing was sent; only a charge never sent before is known not to have happened.
                    if (pending && access is ApiAccess.Unavailable) sales.record(sale.id, SaleEvent.NotSetUp(access.problem))
                    return Failure.NotSetUp(access.problem)
                }
            }
        val result = client.charge(request(sale), idempotencyKey(sale.id))
        sales.record(sale.id, SaleEvent.CardAnswered(result, clock.millis()))
        val start =
            if (result is CardChargeResult.Failed &&
                result.fault.mayHaveTakenEffect
            ) {
                null
            } else {
                synchronized(unresolved) { unresolved.remove(sale.id) }
            }
        if (result is CardChargeResult.Authorised && start != null) onCharged(sale.id, start)
        return (result as? CardChargeResult.Failed)?.takeIf { it.fault.mayHaveTakenEffect }?.let { Failure.Remote(it.fault) }
    }

    /** Stores sale [id] as unknown after the device failed while charging it. */
    private suspend fun interrupted(id: String) {
        try {
            sales.record(id, SaleEvent.OutcomeUnknown(Failure.Device(DeviceFault.DATABASE)))
        } catch (ignored: SQLException) {
            // It stays PENDING, which the next start turns into UNKNOWN.
        }
    }

    /** Values shared with tests and history. */
    companion object {
        /** How long after a charge was opened its unknown outcome may be sent again: Adyen keeps keys at least this long. */
        val RECHECK_WINDOW: Duration = Duration.ofDays(7)

        /** The idempotency key of the charge of sale [saleId]: one charge per sale, however often it is sent. */
        fun idempotencyKey(saleId: String): String = "card-$saleId"

        /** The charge of the stored saved-card [sale], built only from what the sale stored. */
        internal fun request(sale: SaleEntity): CardChargeRequest =
            CardChargeRequest(
                storedPaymentMethodId = checkNotNull(sale.chargedCardId),
                shopperReference = checkNotNull(sale.shopperReference),
                amount = ModificationAmount(sale.currency, sale.totalMinor),
                reference = sale.merchantReference,
                recurringProcessingModel = checkNotNull(sale.chargeRecurringModel),
                shopperEmail = sale.shopperEmail,
            )

        /**
         * The cards simulated payments saved under [shopperReference] among [items], newest first: only simulated and
         * sample sales count, never a real payment's token.
         */
        fun simulatedCards(
            items: List<HistoryItem>,
            shopperReference: String,
        ): List<StoredCard> =
            items
                .asSequence()
                .filterIsInstance<HistoryItem.Sale>()
                .map { it.sale }
                .filter { it.shopperReference == shopperReference && it.simulatedSale }
                .sortedByDescending(SaleEntity::createdAt)
                .mapNotNull(::savedCard)
                .distinctBy(StoredCard::id)
                .toList()

        private fun savedCard(sale: SaleEntity): StoredCard? =
            sale.storedPaymentMethodId?.let { id ->
                StoredCard(
                    id,
                    sale.paymentBrand,
                    sale.maskedPan
                        ?.filter(Char::isDigit)
                        ?.takeLast(LAST_FOUR)
                        ?.ifEmpty { null },
                )
            }

        private const val LAST_FOUR = 4
    }
}
