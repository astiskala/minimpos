package app.minimpos.app.payment

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.GiftCardCategory
import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.GiftCardAction
import app.minimpos.app.data.repo.GiftCardRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.ids.Ids
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.GiftCardAccount
import app.minimpos.terminal.client.GiftCardBalance
import app.minimpos.terminal.client.GiftCardEntryMode
import app.minimpos.terminal.client.GiftCardPayment
import app.minimpos.terminal.client.GiftCardUse
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.StoredValueOperation
import app.minimpos.terminal.client.StoredValueParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.transport.Fault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * A gift card as staff present it for one request: a configured provider and one of its entry methods. A scanned card
 * carries its number transiently (see [GiftCardAccount]); swiped and keyed cards are read by the terminal.
 *
 * @property setting The configured provider.
 * @property account The card for the request.
 */
class PresentedGiftCard private constructor(
    val setting: GiftCardSetting,
    val account: GiftCardAccount,
) {
    /** How it was presented. */
    val entry: GiftCardEntry
        get() =
            when (account.entryMode) {
                GiftCardEntryMode.SCANNED -> GiftCardEntry.SCANNED
                GiftCardEntryMode.MAG_STRIPE -> GiftCardEntry.SWIPED
                GiftCardEntryMode.KEYED -> GiftCardEntry.KEYED
            }

    /** Erases a scanned number once the request was built or abandoned. */
    fun discard() = account.discard()

    override fun toString(): String = "PresentedGiftCard(provider=${setting.provider}, entry=$entry)"

    /** Presenting configured cards only. */
    companion object {
        /**
         * The card [setting] presents by [entry]; a scanned one needs its [number] and [expiryDate] (`MMYY`). Null when
         * [setting] is incomplete, does not allow [entry], or the scanned details are invalid.
         */
        fun of(
            setting: GiftCardSetting,
            entry: GiftCardEntry,
            number: String? = null,
            expiryDate: String? = null,
        ): PresentedGiftCard? {
            if (!setting.complete || entry !in setting.entries) return null
            val account =
                when (entry) {
                    GiftCardEntry.SCANNED -> {
                        runCatching { GiftCardAccount.scanned(setting.wireCode, number.orEmpty(), expiryDate.orEmpty()) }.getOrNull()
                    }

                    GiftCardEntry.SWIPED -> {
                        GiftCardAccount.onTerminal(setting.wireCode, GiftCardEntryMode.MAG_STRIPE)
                    }

                    GiftCardEntry.KEYED -> {
                        GiftCardAccount.onTerminal(setting.wireCode, GiftCardEntryMode.KEYED)
                    }
                }
            return account?.let { PresentedGiftCard(setting, it) }
        }
    }
}

/** How the value of an activation or load is paid for. */
enum class GiftCardFunding {
    /** A card payment on the terminal, before the value is sent. */
    CARD,

    /** Funds staff confirmed they received outside the app. */
    EXTERNAL,
}

/** One stored-value activity or follow-up staff start, see [GiftCards.start]. Amounts are in minor units of [currency]. */
sealed interface GiftCardRequest {
    /** The currency. */
    val currency: String

    /** What its waiting screen shows. */
    val amountMinor: Long

    /** The card it presents; null for follow-ups that need none. */
    val card: PresentedGiftCard? get() = null

    /**
     * Activates [card], loading [amountMinor] paid for by [funding]; zero activates without a load and needs no
     * funding.
     *
     * @property card The card.
     * @property amountMinor Initial value.
     * @property currency The currency.
     * @property funding How the value is paid for; null only for zero.
     */
    data class Activate(
        override val card: PresentedGiftCard,
        override val amountMinor: Long,
        override val currency: String,
        val funding: GiftCardFunding?,
    ) : GiftCardRequest {
        init {
            require(amountMinor >= 0 && (amountMinor == 0L) == (funding == null)) { "Funding pays for a loaded value" }
        }
    }

    /**
     * Loads [amountMinor] onto [card], paid for by [funding].
     *
     * @property card The card.
     * @property amountMinor The value loaded.
     * @property currency The currency.
     * @property funding How the value is paid for.
     */
    data class Load(
        override val card: PresentedGiftCard,
        override val amountMinor: Long,
        override val currency: String,
        val funding: GiftCardFunding,
    ) : GiftCardRequest {
        init {
            require(amountMinor > 0) { "A load needs an amount" }
        }
    }

    /**
     * Activates or loads again for activity [groupId], whose funding is confirmed and whose value was not delivered.
     *
     * @property groupId The activity.
     * @property card The card, presented again.
     * @property amountMinor The funded value.
     * @property currency The currency.
     */
    data class Continue(
        val groupId: String,
        override val card: PresentedGiftCard,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest

    /**
     * Undoes activity [groupId]'s load.
     *
     * @property groupId The activity.
     * @property amountMinor The value loaded.
     * @property currency The currency.
     */
    data class UndoLoad(
        val groupId: String,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest

    /**
     * Deactivates [card].
     *
     * @property card The card.
     * @property currency The currency.
     */
    data class Deactivate(
        override val card: PresentedGiftCard,
        override val currency: String,
    ) : GiftCardRequest {
        override val amountMinor: Long get() = 0
    }

    /**
     * Pays [amountMinor] of [card]'s value out in cash: its whole balance, or less where the provider allows it.
     *
     * @property card The card.
     * @property amountMinor The value paid out.
     * @property currency The currency.
     */
    data class CashOut(
        override val card: PresentedGiftCard,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest

    /**
     * Refunds [amountMinor] onto [card] without a referenced payment.
     *
     * @property card The card.
     * @property amountMinor The value refunded.
     * @property currency The currency.
     */
    data class Refund(
        override val card: PresentedGiftCard,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest

    /**
     * Refunds activity [groupId]'s funding card payment (a referenced reversal).
     *
     * @property groupId The activity.
     * @property amountMinor The funding amount.
     * @property currency The currency.
     */
    data class RefundFunding(
        val groupId: String,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest

    /**
     * Records that activity [groupId]'s externally received funds were given back.
     *
     * @property groupId The activity.
     * @property amountMinor The funds given back.
     * @property currency The currency.
     */
    data class ReturnFunds(
        val groupId: String,
        override val amountMinor: Long,
        override val currency: String,
    ) : GiftCardRequest
}

/**
 * Why staff cannot start [operation] now: the destination takes no gift cards or is not set up ([blocked]), no
 * configured provider ([setting]) supports it, or a value change requires a missing Manager PIN ([managerConfigured]);
 * null when it can start. Pure; the Gift cards screen shows it.
 */
fun giftCardStartProblem(
    operation: GiftCardOperation,
    setting: GiftCardSetting?,
    blocked: SetupProblem?,
    managerConfigured: Boolean,
): SetupProblem? =
    when {
        blocked != null -> blocked
        setting == null || !setting.complete || !setting.provider.supports(operation) -> SetupProblem.GIFT_CARD_PROVIDER
        operation != GiftCardOperation.BALANCE && !managerConfigured -> SetupProblem.MANAGER_APPROVAL
        else -> null
    }

/** What reading a gift card's balance came to, see [GiftCards.balance]. */
sealed interface GiftCardBalanceResult {
    /**
     * The balance.
     *
     * @property balanceMinor In minor units of [currency].
     * @property currency ISO 4217 code.
     * @property lastFour The card's last four digits; null when unknown.
     */
    data class Read(
        val balanceMinor: Long,
        val currency: String,
        val lastFour: String?,
    ) : GiftCardBalanceResult

    /**
     * No balance: refused, unreachable or not set up.
     *
     * @property failure Why, when the app words it; null for a refusal.
     * @property message The terminal's own words, verbatim; null when none.
     * @property cancelled Whether it was cancelled on the terminal.
     */
    data class NotRead(
        val failure: Failure?,
        val message: String? = null,
        val cancelled: Boolean = false,
    ) : GiftCardBalanceResult
}

/**
 * Sends every stored-value operation: activations and loads with their linked funding stage, undoing a load,
 * deactivation, cash-out with its cash handover, unreferenced gift-card refunds and the repairs of funding that
 * delivered no value. Each stage is stored PENDING before sending and settled in place, linked by its activity; the
 * business intent ([GiftCardStage]) and its message category are kept apart, and a status check asks for the category
 * the stage was sent with. Funding that is unknown stops before any value is sent; an unknown value change stays
 * unknown until checked; a load that failed after confirmed funding is offered for repair, never repeated
 * automatically.
 *
 * One activity runs at a time, on [scope] so it survives navigation; [state] follows it like other financial
 * operations. Value changes, funding repairs and cash handovers follow their Manager permission, checked again
 * immediately before sending; balance inquiries need none.
 *
 * @param scope Application scope.
 * @param gateway Sends the requests.
 * @param records Stored stages.
 * @param cash Records cash handed over.
 * @param approval Manager approval held by each activity.
 * @param busy Whether another financial operation is being sent now, which refuses a start.
 * @param blocked Why gift-card requests cannot be sent on the current destination (Tap to Pay), which refuses a start.
 * @param environment The current history environment, kept with local stages.
 * @param onFinished Called with an activity whose main stage succeeded while the app runs, to arm its receipt.
 * @param referencePrefix The configured merchant reference prefix, read per start.
 * @param clock Stamps stages and references.
 * @param zone The time zone of generated references.
 * @param newId Generates stage and activity IDs.
 * @param newServiceId Generates ServiceIDs.
 */
class GiftCards(
    scope: CoroutineScope,
    private val gateway: TerminalGateway,
    private val records: GiftCardRepository,
    private val cash: CashTenders,
    private val approval: ManagerApproval,
    private val busy: () -> Boolean = { false },
    private val blocked: () -> SetupProblem? = { null },
    private val environment: () -> String? = { null },
    private val onFinished: (groupId: String) -> Unit = {},
    private val referencePrefix: () -> String = { "" },
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val newId: () -> String = { Uuid.random().toString() },
    private val newServiceId: () -> String = { TerminalClient.randomServiceId() },
) {
    private val runner =
        OperationRunner<GiftCardRequest>(scope, onTerminal = true) { OperationAmount(it.amountMinor, it.currency) }
    private val valueApproval = approval.forAction(ManagerAction.GIFT_CARD_CHANGE)

    /** The activity running or just finished, by its group ID; waiting and result screens follow it. */
    val state: StateFlow<TransactionState> = runner.state

    /** Sends each stage and checks unknown ones again. */
    private val sends = TerminalSends(scope, gateway, runner.state)
    private val current = mutableMapOf<String, String>()

    /** Whether an original terminal request is being checked now; setup and history maintenance wait for it. */
    internal val checking: Boolean get() = sends.checking

    /** Releases process-only requests after an explicitly confirmed complete history purge committed. */
    internal fun forgetAll() = runner.forgetAll()

    /** Releases process-only requests of activities history has durably deleted. */
    internal fun forget(ids: Set<String>) = runner.forget(ids)

    /**
     * Starts [request] in the background and returns its activity's group ID; null, with nothing stored or sent, while
     * an activity or another financial operation is running or the destination takes no gift cards. Takes the Manager
     * approval held now.
     */
    fun start(request: GiftCardRequest): String? {
        if (busy() || blocked() == SetupProblem.GIFT_CARDS) return null
        val grant = approval.grant()
        val groupId = request.groupId() ?: newId()
        return runner.start(
            request,
            id = { groupId },
            interrupted = { id, _ ->
                current[id]?.let { stage ->
                    val unexpected = StoredReason.Unconfirmed(Failure.Device(DeviceFault.UNEXPECTED))
                    records.settle(stage, GiftCardStatus.UNKNOWN, null, null, unexpected)
                }
            },
            release = {
                request.card?.discard()
                current.remove(groupId)
                runner.resolved(groupId)
            },
        ) { id ->
            if (run(id, request, grant)) onFinished(id)
        }
    }

    /**
     * Asks the terminal to stop the running activity's current request once it is being sent; the outcome still
     * arrives through the request itself.
     */
    fun cancel() = sends.cancel()

    /** Returns to [TransactionState.Idle] once the result has been seen. */
    fun acknowledge() = runner.acknowledge()

    /** Reads [card]'s balance; nothing is stored and nothing changes on the card. */
    suspend fun balance(card: PresentedGiftCard): GiftCardBalanceResult {
        if (!approval.forAction(ManagerAction.GIFT_CARD_BALANCE).allowsNow()) {
            card.discard()
            return GiftCardBalanceResult.NotRead(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL))
        }
        if (!card.setting.provider.supports(GiftCardOperation.BALANCE)) {
            card.discard()
            return GiftCardBalanceResult.NotRead(Failure.Remote(Fault.Unsupported))
        }
        val attempt =
            try {
                gateway.balance(card.account)
            } finally {
                card.discard()
            }
        return when (attempt) {
            is Attempt.NotSetUp -> {
                GiftCardBalanceResult.NotRead(Failure.NotSetUp(attempt.problem))
            }

            is Attempt.Made -> {
                when (val result = attempt.result) {
                    is GiftCardBalance.Read -> {
                        GiftCardBalanceResult.Read(result.balanceMinor, result.currency, result.lastFour)
                    }

                    is GiftCardBalance.Refused -> {
                        GiftCardBalanceResult.NotRead(
                            null,
                            result.message?.text,
                            result.decline?.cancelled == true,
                        )
                    }

                    is GiftCardBalance.Failed -> {
                        GiftCardBalanceResult.NotRead(Failure.Remote(result.fault))
                    }
                }
            }
        }
    }

    /**
     * Asks the terminal again about activity [groupId]'s stage whose outcome is unknown, with its original message
     * category and context, and stores the answer. Settled funding never continues by itself.
     */
    suspend fun recheck(groupId: String): Recheck =
        sends.recheck(unresolved = {
            val stage = records.activity(groupId)?.stages?.firstOrNull { it.status == GiftCardStatus.UNKNOWN && it.stage.sent }
            stage?.serviceId?.let { UnresolvedRequest(it, kindOf(checkNotNull(stage.category)), stage.context, record(stage.id)) }
        })

    /**
     * Records that the cash of activity [groupId]'s approved cash-out was handed over, after its configured Manager approval;
     * false, storing nothing, without approval or when nothing is awaiting.
     */
    suspend fun confirmHandover(groupId: String): Boolean {
        if (!valueApproval.allowsConfigured(approval.grant())) return false
        val stage =
            records.activity(groupId)?.stages?.firstOrNull {
                it.stage == GiftCardStage.CASH_HANDOVER && it.status == GiftCardStatus.AWAITING
            } ?: return false
        return cash.handOver(stage.id)
    }

    /** The existing activity a follow-up belongs to; null for a request that starts a new one. */
    private fun GiftCardRequest.groupId(): String? =
        when (this) {
            is GiftCardRequest.Continue -> groupId

            is GiftCardRequest.UndoLoad -> groupId

            is GiftCardRequest.RefundFunding -> groupId

            is GiftCardRequest.ReturnFunds -> groupId

            is GiftCardRequest.Activate, is GiftCardRequest.Load, is GiftCardRequest.Deactivate,
            is GiftCardRequest.CashOut, is GiftCardRequest.Refund,
            -> null
        }

    /**
     * One stage to store for activity [groupId]: what it does ([stage]) with [card] (null when it needs none) for
     * [amountMinor] of [currency].
     */
    private class Change(
        val groupId: String,
        val card: PresentedGiftCard?,
        val amountMinor: Long,
        val currency: String,
        val stage: GiftCardStage,
    )

    /** Confirmed funding of a value: the original context of its card payment, null when paid outside the app or none. */
    private class Funded(
        val context: PaymentContext?,
    )

    /** Runs [request] as activity [groupId]; true when its main stage succeeded. */
    private suspend fun run(
        groupId: String,
        request: GiftCardRequest,
        grant: ManagerApproval.Grant,
    ): Boolean =
        when (request) {
            is GiftCardRequest.Activate -> {
                funded(Change(groupId, request.card, request.amountMinor, request.currency, GiftCardStage.ACTIVATE), request.funding, grant)
            }

            is GiftCardRequest.Load -> {
                funded(Change(groupId, request.card, request.amountMinor, request.currency, GiftCardStage.LOAD), request.funding, grant)
            }

            is GiftCardRequest.Continue -> {
                continued(request, grant)
            }

            is GiftCardRequest.UndoLoad -> {
                undo(groupId, grant)
            }

            is GiftCardRequest.Deactivate -> {
                change(Change(groupId, request.card, 0, request.currency, GiftCardStage.DEACTIVATE), grant)
            }

            is GiftCardRequest.CashOut -> {
                change(Change(groupId, request.card, request.amountMinor, request.currency, GiftCardStage.CASH_OUT), grant)
            }

            is GiftCardRequest.Refund -> {
                change(Change(groupId, request.card, request.amountMinor, request.currency, GiftCardStage.REFUND), grant)
            }

            is GiftCardRequest.RefundFunding -> {
                refundFunding(groupId, grant)
            }

            is GiftCardRequest.ReturnFunds -> {
                returnFunds(groupId, grant)
            }
        }

    /** Funds [change] as [funding] says, then sends the value once the funding is confirmed. */
    private suspend fun funded(
        change: Change,
        funding: GiftCardFunding?,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val supported =
            change.stage.operation?.let {
                change.card
                    ?.setting
                    ?.provider
                    ?.supports(it)
            } == true
        val funded = if (supported) fund(change, funding, grant) else null
        return funded != null && value(change, grant, funded.context)
    }

    private suspend fun fund(
        change: Change,
        funding: GiftCardFunding?,
        grant: ManagerApproval.Grant,
    ): Funded? =
        when (funding) {
            null -> Funded(null)
            GiftCardFunding.CARD -> fundByCard(change, grant)
            GiftCardFunding.EXTERNAL -> fundExternally(change, grant)
        }

    private suspend fun fundByCard(
        change: Change,
        grant: ManagerApproval.Grant,
    ): Funded? {
        val fund = stage(change, GiftCardStage.FUNDING_CARD, funds = change.stage)
        val paid =
            records.open(fund) &&
                send(
                    fund,
                    grant,
                    TerminalOperation.Pay(
                        PaymentParams(change.amountMinor, change.currency, fund.merchantReference, appPrintsReceipts = true),
                    ),
                ) == GiftCardStatus.SUCCEEDED
        return if (paid) Funded(records.get(fund.id)?.context) else null
    }

    private suspend fun fundExternally(
        change: Change,
        grant: ManagerApproval.Grant,
    ): Funded? {
        val fund =
            stage(change, GiftCardStage.FUNDING_EXTERNAL, funds = change.stage).copy(
                status = GiftCardStatus.SUCCEEDED,
                processedAt = clock.millis(),
                approvedMinor = change.amountMinor,
                environment = environment(),
            )
        return Funded(null).takeIf { valueApproval.allowsConfigured(grant) && records.open(fund) }
    }

    /** Activates or loads again for an activity whose funding is confirmed and whose value was not delivered. */
    private suspend fun continued(
        request: GiftCardRequest.Continue,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val activity = records.activity(request.groupId)?.takeIf { GiftCardAction.CONTINUE in it.attention }
        val funding = activity?.funding ?: return false
        // The value delivered is the confirmed funding, not what the screen last read.
        val change = Change(request.groupId, request.card, funding.movedMinor, funding.currency, activity.kind)
        return value(change, grant, funding.context)
    }

    /** Sends [change]'s activation or load, once its funding (if any) is confirmed and nothing is unresolved. */
    private suspend fun value(
        change: Change,
        grant: ManagerApproval.Grant,
        expected: PaymentContext?,
    ): Boolean {
        val card = checkNotNull(change.card)
        val entity = stage(change)
        val operation = if (change.stage == GiftCardStage.ACTIVATE) StoredValueOperation.ACTIVATE else StoredValueOperation.LOAD
        // A new activity without funding, or confirmed funding that has delivered no value and is not being repaired.
        return records.open(entity) { it == null || GiftCardAction.CONTINUE in it.attention } &&
            send(
                entity,
                grant,
                TerminalOperation.StoredValue(
                    StoredValueParams(operation, card.account, change.amountMinor, change.currency, entity.merchantReference),
                    expected,
                ),
            ) == GiftCardStatus.SUCCEEDED
    }

    private suspend fun undo(
        groupId: String,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val now = clock.millis()
        val activity = records.activity(groupId)?.takeIf { GiftCardAction.UNDO_LOAD in it.actions(now, records.recentActivities(now)) }
        val load = activity?.value ?: return false
        val entity =
            stage(Change(groupId, null, load.movedMinor, load.currency, GiftCardStage.UNDO_LOAD)).copy(
                provider = load.provider,
                providerCode = load.providerCode,
                cardLastFour = load.cardLastFour,
                originalId = load.id,
            )
        val original = load.pspReference?.let { ".$it" } ?: checkNotNull(load.poiTransactionId)
        return records.open(entity) { it != null && GiftCardAction.UNDO_LOAD in it.actions(now) } &&
            send(
                entity,
                grant,
                TerminalOperation.StoredValue(
                    StoredValueParams(
                        StoredValueOperation.UNDO_LOAD,
                        null,
                        entity.amountMinor,
                        entity.currency,
                        entity.merchantReference,
                        original,
                        checkNotNull(load.poiTimestamp),
                    ),
                    load.context,
                ),
            ) == GiftCardStatus.SUCCEEDED
    }

    /** Sends a value change without funding: deactivation, cash-out or an unreferenced refund onto the card. */
    private suspend fun change(
        change: Change,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val card = checkNotNull(change.card)
        val supported = change.stage.operation?.let { card.setting.provider.supports(it) } == true
        val entity = stage(change)
        val request =
            if (change.stage == GiftCardStage.DEACTIVATE) {
                TerminalOperation.StoredValue(
                    StoredValueParams(StoredValueOperation.DEACTIVATE, card.account, 0, change.currency, entity.merchantReference),
                )
            } else {
                val use = if (change.stage == GiftCardStage.CASH_OUT) GiftCardUse.CASH_OUT else GiftCardUse.REFUND
                TerminalOperation.Pay(
                    PaymentParams(
                        change.amountMinor,
                        change.currency,
                        entity.merchantReference,
                        appPrintsReceipts = true,
                        giftCard = GiftCardPayment(card.account, use),
                    ),
                )
            }
        return supported && records.open(entity) && send(entity, grant, request) == GiftCardStatus.SUCCEEDED
    }

    private suspend fun refundFunding(
        groupId: String,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val activity = records.activity(groupId)
        val funding = activity?.funding?.takeIf { GiftCardAction.REFUND_FUNDING in activity.attention } ?: return false
        val entity =
            stage(Change(groupId, null, funding.movedMinor, funding.currency, GiftCardStage.FUNDING_REFUND)).copy(originalId = funding.id)
        return records.open(entity) { GiftCardAction.REFUND_FUNDING in it?.attention.orEmpty() } &&
            send(
                entity,
                grant,
                TerminalOperation.Refund(
                    RefundParams(checkNotNull(funding.poiTransactionId), checkNotNull(funding.poiTimestamp), entity.merchantReference),
                    OperationAmount(entity.amountMinor, entity.currency),
                    expected = funding.context,
                ),
            ) == GiftCardStatus.SUCCEEDED
    }

    private suspend fun returnFunds(
        groupId: String,
        grant: ManagerApproval.Grant,
    ): Boolean {
        val funding =
            records.activity(groupId)?.funding?.takeIf { valueApproval.allowsConfigured(grant) }
                ?: return false
        val entity =
            stage(Change(groupId, null, funding.movedMinor, funding.currency, GiftCardStage.FUNDING_RETURNED)).copy(
                status = GiftCardStatus.SUCCEEDED,
                processedAt = clock.millis(),
                approvedMinor = funding.movedMinor,
                originalId = funding.id,
                environment = environment(),
            )
        return records.open(entity) { GiftCardAction.RETURN_FUNDS in it?.attention.orEmpty() }
    }

    /**
     * Sends stored PENDING [entity] as [request] once Manager approval still holds, and stores its settlement; the
     * stored status, null when the stage proved gone. Without approval nothing is sent and it is stored as failed.
     */
    private suspend fun send(
        entity: GiftCardOperationEntity,
        grant: ManagerApproval.Grant,
        request: TerminalOperation,
    ): GiftCardStatus? {
        current[entity.groupId] = entity.id
        val settlement =
            sends.send(entity.groupId, checkNotNull(entity.serviceId), request, record(entity.id)) {
                valueApproval.allowsConfigured(grant)
            }
        current.remove(entity.groupId)
        return settlement?.status?.let(::statusOf)
    }

    /** Stage [id] as [records] store it. */
    private fun record(id: String) =
        object : TerminalRecord {
            override suspend fun sending(poiId: String) {
                records.sending(id, poiId)
            }

            override suspend fun recordContext(context: PaymentContext) {
                records.recordContext(id, context)
            }

            override suspend fun settle(settlement: Settlement): Boolean =
                records.settle(id, statusOf(settlement.status), settlement.message, settlement.details, settlement.reason)
        }

    private fun statusOf(status: SettlementStatus): GiftCardStatus =
        when (status) {
            SettlementStatus.SUCCEEDED -> GiftCardStatus.SUCCEEDED
            SettlementStatus.CANCELLED -> GiftCardStatus.CANCELLED
            SettlementStatus.DECLINED -> GiftCardStatus.DECLINED
            SettlementStatus.FAILED -> GiftCardStatus.FAILED
            SettlementStatus.UNKNOWN -> GiftCardStatus.UNKNOWN
        }

    /** [change] as a new PENDING (sent) or local stage of kind [stage], before anything is sent. */
    private fun stage(
        change: Change,
        stage: GiftCardStage = change.stage,
        funds: GiftCardStage? = null,
    ): GiftCardOperationEntity {
        val now = clock.instant()
        val card = change.card
        return GiftCardOperationEntity(
            id = newId(),
            groupId = change.groupId,
            stage = stage,
            category = stage.category,
            createdAt = now.toEpochMilli(),
            status = GiftCardStatus.PENDING,
            provider = card?.setting?.provider?.name,
            providerCode = card?.setting?.wireCode.takeIf { funds == null },
            entry = card?.entry?.name,
            cardLastFour = card?.account?.lastFour,
            currency = change.currency,
            amountMinor = change.amountMinor,
            merchantReference = Ids.transactionReference("$REFERENCE_PREFIX${referencePrefix()}", now, zone()),
            serviceId = newServiceId().takeIf { stage.sent },
            funds = funds,
        )
    }

    private fun kindOf(category: GiftCardCategory): TransactionKind =
        when (category) {
            GiftCardCategory.PAYMENT -> TransactionKind.PAYMENT
            GiftCardCategory.STORED_VALUE -> TransactionKind.STORED_VALUE
            GiftCardCategory.REVERSAL -> TransactionKind.REFUND
        }

    private companion object {
        /** Gift-card merchant references start `G-`, beside refunds' `R-` and cancellations' `C-`. */
        const val REFERENCE_PREFIX = "G-"
    }
}
