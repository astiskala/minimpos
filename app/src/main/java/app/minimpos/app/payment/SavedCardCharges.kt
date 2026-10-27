package app.minimpos.app.payment

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.history.simulatedSale
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardDeletion
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.transport.Fault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * A saved-card charge rung up in its own session: what checkout made of the cart, and the card chosen for it.
 *
 * @property payment The priced cart, references and shopper reference; an immediate-charge sale with a shopper reference.
 * @property card The saved card to charge.
 * @property recurringModel Adyen's `recurringProcessingModel` to charge it with, frozen with the sale.
 * @property fromCheckout Whether the sale was rung up as a New sale and charged from its checkout, so that session
 *   (rather than Tokens’ own) is cleared once the token is charged.
 * @property lookupContext Original context of the selected listing; null only for context-free tests or direct test starts.
 * @throws IllegalArgumentException for a payment that is not an immediate-charge sale with a shopper reference.
 */
data class SavedCardStart(
    val payment: PaymentStart,
    val card: StoredCard,
    val recurringModel: String,
    val fromCheckout: Boolean = false,
    internal val lookupContext: PaymentContext? = null,
) {
    init {
        require(payment.kind == SaleKind.SALE && !payment.manualCapture) { "A saved card is charged straight away" }
        requireNotNull(payment.shopperReference) { "A saved card is charged for a shopper reference" }
    }

    /** Retains the listed [card] and its [context], so a queued charge cannot move to a different merchant or environment. */
    internal fun forLookup(
        card: StoredCard,
        context: PaymentContext?,
    ): SavedCardStart = copy(card = card, lookupContext = context)
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
 * Adyen answers what it did the first time instead of charging twice. [CheckoutOperations] runs them: Manager approval
 * is checked again immediately before anything is sent, an unknown charge is sent again only within
 * [OperationIdentity.REPLAY_WINDOW], and charges and checks run one at a time.
 *
 * @param scope Where charges run, so one survives leaving the screen.
 * @param sales Where the sales are stored.
 * @param target Where saved cards go now: [app.minimpos.app.terminal.AdyenApi.target] in the app.
 * @param onCharged Called once with the sale ID and its original start when its charge is approved in this process,
 *   to clear its originating session and arm automatic delivery.
 * @param clock Stamps the sales and decides whether an unknown charge may be sent again.
 * @param newId Generates sale IDs.
 * @param approval Manager approval, which every charge needs: taken when it starts, checked again before sending.
 * @param targets Current targets, including credential changes; fixed-target tests may use the default one reading.
 */
class SavedCardCharges(
    private val scope: CoroutineScope,
    sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    onCharged: (id: String, start: SavedCardStart) -> Unit = { _, _ -> },
    clock: Clock = Clock.systemUTC(),
    newId: () -> String = { Uuid.random().toString() },
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
    private val targets: Flow<ApiTarget> = flow { emit(target()) },
) {
    private val mutex = Mutex()
    private val operations =
        CheckoutOperations(
            scope,
            Book(sales),
            target,
            onCharged,
            clock,
            newId,
            approval.forActions(setOf(ManagerAction.SALE, ManagerAction.SAVED_CARD_CHARGE)),
            mutex,
        )
    private var deletionOrigin: ApiTarget? = null

    /** Invalidates every explicit and automatic selection synchronously when token management changes its evidence. */
    internal val tokenRevision: StateFlow<Long>
        field = MutableStateFlow(0L)

    /** Latest deletion and its read-only reconciliation; retained across navigation, never replayed after restart. */
    val deletion: StateFlow<TokenDeletionState>
        field = MutableStateFlow(TokenDeletionState())

    /** Whether token management is sending or reconciling an API request; setup changes wait for it. */
    val managing: Boolean get() = deletion.value.status in setOf(TokenDeletionStatus.DELETING, TokenDeletionStatus.CHECKING)

    internal val unresolvedDeletion: Boolean
        get() = managing || deletion.value.status == TokenDeletionStatus.UNKNOWN

    /** The charge running now, which the charging screen follows to its result. */
    val state: StateFlow<TransactionState> = operations.state

    /** Saved-card charges as [FinancialOperations] holds them: busy while a charge runs or token management is sending. */
    internal val operationKind = OperationKind({ managing || operations.operationKind.running }, operations::forget, ::forgetAll)

    /**
     * Makes a memory-only lookup for [checkouts] and [terminal] in [scope]. Null [delay] requires explicit lookup;
     * otherwise each unchanged eligible reference is looked up after that delay.
     */
    fun lookup(
        scope: CoroutineScope,
        checkouts: Flow<Checkout>,
        terminal: Flow<TerminalState>,
        delay: Duration? = null,
    ): SavedCardLookup = SavedCardLookup(scope, checkouts, terminal, targets, this, delay)

    /** Lists [shopperReference]'s cards in the current target without storing anything. */
    internal suspend fun cards(shopperReference: String): CardLookup = cards(shopperReference, target())

    /** Lists [shopperReference]'s cards only while [origin] is still current, including after the answer; stores nothing. */
    internal suspend fun cards(
        shopperReference: String,
        origin: ApiTarget,
    ): CardLookup {
        if (!approval
                .forAction(
                    ManagerAction.SAVED_CARD_LOOKUP,
                ).allowsNow()
        ) {
            return CardLookup.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL))
        }
        if (target() != origin) return CardLookup.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
        val result =
            when (val access = origin.savedCards(origin.context)) {
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
        return if (target() == origin) result else CardLookup.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
    }

    /**
     * Charges [start] in the background and returns the new sale's ID straight away; null, with nothing stored or sent,
     * while another charge is running (such as a second tap on the same action).
     */
    fun start(start: SavedCardStart): String? =
        synchronized(this) {
            if (unresolvedDeletion) null else operations.start(start)
        }

    /** Starts a charge only while its listing revision remains current, including before flow collectors catch up. */
    internal fun start(
        start: SavedCardStart,
        revision: Long,
    ): String? =
        synchronized(this) {
            if (tokenRevision.value != revision) null else start(start)
        }

    /** Starts a confirmed deletion only for a current listed token; approval is rechecked immediately before DELETE. */
    internal fun delete(
        card: StoredCard,
        reference: String,
        origin: ApiTarget,
        revision: Long,
    ): Boolean =
        synchronized(this) {
            if (unresolvedDeletion || state.value is TransactionState.Processing) return false
            if (mutex.isLocked || tokenRevision.value != revision) return false
            val grant = approval.grant()
            deletionOrigin = origin
            deletion.value = TokenDeletionState(card, reference, TokenDeletionStatus.DELETING)
            tokenRevision.value++
            scope.launch {
                mutex.withLock {
                    val result =
                        try {
                            when {
                                target() != origin -> {
                                    TokenDeletionState(
                                        card,
                                        reference,
                                        TokenDeletionStatus.FAILED,
                                        Failure.NotSetUp(SetupProblem.SETUP_CHANGED),
                                    )
                                }

                                !approval.forAction(ManagerAction.TOKEN_DELETE).allows(grant) -> {
                                    TokenDeletionState(
                                        card,
                                        reference,
                                        TokenDeletionStatus.FAILED,
                                        Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL),
                                    )
                                }

                                else -> {
                                    deleteAtOrigin(card, reference, origin)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            TokenDeletionState(card, reference, TokenDeletionStatus.UNKNOWN, Failure.Device(DeviceFault.UNEXPECTED))
                        }
                    finishDeletion(result)
                }
            }
            true
        }

    private suspend fun deleteAtOrigin(
        card: StoredCard,
        reference: String,
        origin: ApiTarget,
    ): TokenDeletionState {
        val previous = TokenDeletionState(card, reference, TokenDeletionStatus.UNKNOWN)
        val access = origin.savedCards(origin.context)
        if (access is ApiAccess.Blocked) {
            return previous.copy(status = TokenDeletionStatus.FAILED, failure = Failure.NotSetUp(access.problem))
        }
        val result = (access as ApiAccess.Ready).client.delete(reference, card.id)
        return when (result) {
            StoredCardDeletion.Deleted -> {
                previous.copy(status = TokenDeletionStatus.DELETED)
            }

            is StoredCardDeletion.Failed -> {
                val uncertain = result.fault.mayHaveTakenEffect || (result.fault as? Fault.AdyenRejected)?.http == HTTP_TOKEN_MISSING
                val failed =
                    previous.copy(
                        status = if (uncertain) TokenDeletionStatus.UNKNOWN else TokenDeletionStatus.FAILED,
                        failure = Failure.Remote(result.fault),
                    )
                if (uncertain) reconcile(failed, origin) else failed
            }
        }
    }

    /** Reconciles by listing the original account/environment with current eligible credentials; never replays DELETE. */
    fun recheckDeletion(): Boolean =
        synchronized(this) {
            val previous = deletion.value
            val origin = deletionOrigin ?: return false
            if (previous.status != TokenDeletionStatus.UNKNOWN) return false
            if (state.value is TransactionState.Processing || mutex.isLocked) return false
            deletion.value = previous.copy(status = TokenDeletionStatus.CHECKING)
            scope.launch {
                mutex.withLock {
                    val result =
                        try {
                            reconcile(previous, origin)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            previous.copy(failure = Failure.Device(DeviceFault.UNEXPECTED))
                        }
                    finishDeletion(result)
                }
            }
            true
        }

    private suspend fun reconcile(
        previous: TokenDeletionState,
        origin: ApiTarget,
    ): TokenDeletionState {
        val current = target()
        // Tokens belong to the merchant/shopper, not an SDK client instance. A restored or rotated credential may
        // read their status, just as it may check a historical payment, only after original-context validation.
        val access = current.savedCards(origin.context)
        if (access is ApiAccess.Blocked) {
            return previous.copy(status = TokenDeletionStatus.UNKNOWN, failure = Failure.NotSetUp(access.problem))
        }
        val found = cards(checkNotNull(previous.reference), current)
        return when (found) {
            is CardLookup.NotDone -> {
                previous.copy(status = TokenDeletionStatus.UNKNOWN, failure = found.failure)
            }

            is CardLookup.Listed -> {
                val absent = found.cards.none { it.id == previous.card?.id }
                previous.copy(status = if (absent) TokenDeletionStatus.DELETED else TokenDeletionStatus.UNKNOWN, failure = null)
            }
        }
    }

    private fun finishDeletion(result: TokenDeletionState) =
        synchronized(this) {
            deletion.value = result
            tokenRevision.value++
        }

    /** Releases process-only requests after an explicitly confirmed complete history purge committed. */
    internal fun forgetAll() =
        synchronized(this) {
            operations.forgetAll()
            deletionOrigin = null
            deletion.value = TokenDeletionState()
            tokenRevision.value++
        }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next charge can start. */
    fun acknowledge() = operations.acknowledge()

    /**
     * Sends the charge of sale [saleId], whose outcome is unknown, again with its stored request and idempotency key, and
     * stores Adyen's answer, as [CheckoutOperations.recheck] does; anything but an unknown saved-card charge has nothing
     * left to check ([Recheck.Settled]). Callers check through [FinancialOperations.recheck].
     */
    suspend fun recheck(saleId: String): Recheck = operations.recheck(saleId)

    /** Saved-card charges as stored sales, sent with [SavedCardApi.charge] built only from what the sale stored. */
    private class Book(
        private val sales: SaleRepository,
    ) : CheckoutBook<SavedCardStart, SavedCardApi> {
        override fun amount(request: SavedCardStart): OperationAmount = request.payment.amount

        override suspend fun open(
            id: String,
            request: SavedCardStart,
            createdAt: Long,
            current: PaymentContext?,
        ) = sales.createPending(
            SaleBook.savedCardSale(id, request, createdAt, request.lookupContext ?: current),
            SaleBook.lines(id, request.payment.totals),
        )

        override suspend fun unsent(id: String): CheckoutRecord? =
            sales
                .get(id)
                ?.sale
                ?.takeIf { it.savedCard && (it.status == SaleStatus.PENDING || it.status == SaleStatus.UNKNOWN) }
                ?.let { CheckoutRecord(it.status == SaleStatus.PENDING, it.createdAt, it.context, it.reason) }

        override fun access(
            target: ApiTarget,
            context: PaymentContext?,
        ): ApiAccess<SavedCardApi> = target.savedCards(context)

        override suspend fun send(
            id: String,
            client: SavedCardApi,
            now: Long,
        ): CheckoutReply {
            val sale = sales.get(id)?.sale ?: return CheckoutReply { CheckoutAnswer.NotTaken }
            val result = client.charge(request(sale), idempotencyKey(id))
            return CheckoutReply((result as? CardChargeResult.Failed)?.fault) { failed ->
                sales.record(id, SaleEvent.CardAnswered(result, now))
                when {
                    result is CardChargeResult.Authorised -> {
                        CheckoutAnswer.Taken
                    }

                    result is CardChargeResult.Failed && failed == FailedSend.UNCONFIRMED -> {
                        CheckoutAnswer.Unconfirmed(
                            Failure.Remote(result.fault),
                        )
                    }

                    else -> {
                        CheckoutAnswer.NotTaken
                    }
                }
            }
        }

        override suspend fun notSent(
            id: String,
            problem: SetupProblem,
        ) {
            sales.record(id, SaleEvent.NotSetUp(problem))
        }

        override suspend fun interrupted(
            id: String,
            fault: DeviceFault,
        ) {
            sales.record(id, SaleEvent.OutcomeUnknown(Failure.Device(fault)))
        }
    }

    /** Values shared with tests and history. */
    companion object {
        private const val LAST_FOUR = 4
        private const val HTTP_TOKEN_MISSING = 404

        /**
         * Why saved cards cannot be found or charged now: no shopper reference is made ([source] is
         * [ShopperReferenceSource.NONE]), a verified import is still saving, or the Checkout API is not set up where
         * [terminal] says payments go; null when they can.
         */
        fun blocked(
            source: ShopperReferenceSource,
            terminal: TerminalState,
        ): Failure? =
            when {
                source == ShopperReferenceSource.NONE -> SetupProblem.SHOPPER_REFERENCE
                terminal.importPending -> SetupProblem.TRANSFER_PENDING
                else -> terminal.apiProblem
            }?.let(Failure::NotSetUp)

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
            items: List<SaleEntity>,
            shopperReference: String,
        ): List<StoredCard> =
            items
                .asSequence()
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
    }
}
