package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.GiftCardRepository
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.MerchantCopyPolicy
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.email.ReceiptEmailer
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.ReceiptStanding
import app.minimpos.app.history.awaitsLinkPayment
import app.minimpos.app.history.standing
import app.minimpos.app.receipt.ActionResult
import app.minimpos.app.receipt.PrintRenderer
import app.minimpos.app.receipt.ReceiptFactory
import app.minimpos.app.receipt.ReceiptKind
import app.minimpos.app.receipt.ReceiptSurface
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalStatus
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.terminal.client.PrintOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A stored transaction whose receipt is delivered, see [ReceiptDelivery]: a sale (taken on a terminal or through a
 * payment link), a refund (or cancellation) or a stored-value activity.
 */
sealed interface StoredTransaction {
    /**
     * A stored sale.
     *
     * @property id The sale's ID.
     */
    data class Sale(
        val id: String,
    ) : StoredTransaction

    /**
     * A stored refund or cancellation.
     *
     * @property id The refund's ID.
     */
    data class Refund(
        val id: String,
    ) : StoredTransaction

    /**
     * A stored-value (gift-card) activity.
     *
     * @property id The activity's group ID.
     */
    data class GiftCard(
        val id: String,
    ) : StoredTransaction
}

/**
 * The result of printing a copy of a transaction's receipt.
 *
 * @property result Whether it printed.
 * @property merchantCopyDue Whether the merchant copy should be printed next: the customer copy of a sale printed and
 *   the merchant copy setting asks for one (always, or when the shopper had to sign); never for a refund.
 */
data class ReceiptPrint(
    val result: ActionResult,
    val merchantCopyDue: Boolean = false,
)

/**
 * What a result or detail screen offers for the receipt of one stored transaction, see [ReceiptDelivery.offer].
 *
 * @property receipt The receipt as it prints now, with the current settings; null while the transaction is not stored.
 * @property canPrint Whether printing is offered.
 * @property canEmail Whether emailing it is offered.
 * @property canShare Whether sharing it through Android's share sheet is offered (not on an Adyen terminal).
 * @property share The receipt to share when asked, as it is emailed; null while the transaction is not stored or
 *   sharing is not offered.
 */
data class ReceiptOffer(
    val receipt: ReceiptDocument?,
    val canPrint: Boolean,
    val canEmail: Boolean,
    val canShare: Boolean = false,
    val share: SharedReceipt? = null,
)

/**
 * A receipt to share through Android's share sheet, see [ReceiptOffer.share]; the screen words the message.
 *
 * @property document The receipt as emailed (no lines to write a tip on).
 * @property reference The transaction's merchant reference.
 * @property amountMinor The transaction's amount, in minor units of [currency].
 * @property currency The transaction's ISO 4217 currency code.
 * @property paymentLink The payment link the sale still awaits its payment through, to share with the receipt; null
 *   for a receipt.
 * @property simulatedLink Whether the underlying sale is a demo link, including after completion or cancellation.
 */
data class SharedReceipt(
    val document: ReceiptDocument,
    val reference: String,
    val amountMinor: Long,
    val currency: String,
    val paymentLink: String? = null,
    val simulatedLink: Boolean = false,
)

/**
 * Progress of one process-owned automatic delivery attempt, see [ReceiptDelivery.automation].
 *
 * @property printing Whether its customer receipt is being printed.
 * @property printed Print outcome; null before completion or when no automatic print is due.
 * @property emailing Whether its receipt is being emailed.
 * @property emailTo Address captured for automatic emailing; null when none is due.
 * @property emailed Email outcome; null before completion or when no automatic email is due.
 */
data class AutoDelivery(
    val printing: Boolean = false,
    val printed: ReceiptPrint? = null,
    val emailing: Boolean = false,
    val emailTo: String? = null,
    val emailed: ActionResult? = null,
)

/** Stored transaction sources shared by receipt and whole-day delivery. */
class ReceiptRecords(
    /** Stored sales; an emailed receipt updates its address here. */
    val sales: SaleRepository,
    /** Stored refunds and their receipt data. */
    val refunds: RefundRepository,
    /** All retained records; reports never use UI filters. */
    val history: HistoryRepository,
    /** Stored-value activities; null where none are kept. */
    val giftCards: GiftCardRepository? = null,
)

/**
 * Receipts of stored sales and refunds, delivered by printing on the terminal (or the simulator's on-screen printer),
 * by email and, off-terminal, through Android's share sheet (the screen shares [ReceiptOffer.share]), with the current
 * receipt settings, and what the screens offer for them ([offer]). Every operation takes the [StoredTransaction], and
 * what differs between sales and refunds is decided here: a sale has a merchant copy, a refund one copy only; the
 * receipt of a sale still awaiting its payment link's payment is an unpaid one with the link, emailed as a payment
 * request. It owns one automatic delivery attempt per [arm] in this process: whichever screen opens first claims it
 * with [automation] through [app.minimpos.app.feature.TransactionActions]; later screens get nothing. Claimed work
 * runs in the application scope even after that screen closes. No durable queue or automatic uncertain-send retry.
 *
 * Missing sales and refunds (for example after pruning) are [ActionResult.Missing]; missing terminal or email setup and
 * delivery errors are [ActionResult.Failed] with the typed failure the screens word.
 *
 * @param settings The receipt, payment and email settings, read for each call.
 * @param records Stored sales, refunds and unfiltered daily activity.
 * @param receipts Builds the receipt documents.
 * @param gateway Prints them.
 * @param status Tells whether printing is offered.
 * @param emailer Emails them.
 * @param scope Owns claimed automatic delivery independently of the screen observing it.
 * @param clock Stamps when a daily summary was generated.
 * @param cpu Dispatcher for receipt construction outside the UI thread.
 */
class ReceiptDelivery(
    private val settings: SettingsRepository,
    private val records: ReceiptRecords,
    private val receipts: ReceiptFactory,
    private val gateway: TerminalGateway,
    private val status: TerminalStatus,
    private val emailer: ReceiptEmailer,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Whether a daily summary can be printed with the current destination and receipt settings. */
    val canPrintReports: Flow<Boolean> = status.state.map { it.printerAvailable }

    /**
     * Prints an unfiltered local summary for [date], counted in [zone] (the zone the day was chosen in) and stamped
     * with one generation time from the clock.
     */
    suspend fun printReport(
        date: LocalDate,
        zone: ZoneId,
    ): ActionResult {
        val current = settings.current()
        val document =
            withContext(cpu) {
                val report =
                    records.history.reader
                        .fold(HistoryAccounting.ReportAccumulator(date, zone, clock.millis())) { total, batch ->
                            total.apply { add(batch) }
                        }.report()
                receipts.dailyReport(report, current.receipt)
            }
        return print(document, current)
    }

    private val armed = mutableSetOf<String>()

    /**
     * What the screens of [transaction] offer for its receipt, updated as it, the settings and the printer change: the
     * receipt, printing while it is offered, and email once it is set up. Right after a sale's payment ([fresh]) email is
     * offered only when checkout captures emails ("Ask for the shopper's email" is not Never), except for a payment link,
     * which reaches the shopper by email; later, and for refunds, whenever email is set up.
     */
    fun offer(
        transaction: StoredTransaction,
        fresh: Boolean = false,
    ): Flow<ReceiptOffer> =
        when (transaction) {
            is StoredTransaction.Sale -> saleOffer(transaction.id, fresh)
            is StoredTransaction.Refund -> refundOffer(transaction.id)
            is StoredTransaction.GiftCard -> giftCardOffer(transaction.id)
        }

    /**
     * Claims and dispatches automatic delivery for [transaction] atomically with respect to other claimants.
     * Printing and emailing share the existing settings policy and run independently; cancelling the observer does
     * not abandon either attempt. The flow gives the latest progress and completes after planning and every due attempt
     * finished; for a transaction that is not armed, or already claimed, it gives one empty reading.
     */
    fun automation(transaction: StoredTransaction): Flow<AutoDelivery> {
        val id =
            when (transaction) {
                is StoredTransaction.Sale -> transaction.id
                is StoredTransaction.Refund -> transaction.id
                is StoredTransaction.GiftCard -> transaction.id
            }
        if (!claim(id)) return flowOf(AutoDelivery())
        val progress = MutableStateFlow(AutoProgress(AutoDelivery()))
        scope.launch {
            val plan =
                when (transaction) {
                    is StoredTransaction.Sale -> {
                        automationForSale(transaction.id)
                    }

                    is StoredTransaction.Refund, is StoredTransaction.GiftCard -> {
                        DeliveryPlan(
                            print = printsAutomatically(settings.current()),
                        )
                    }
                }
            progress.value = AutoProgress(AutoDelivery(printing = plan.print, emailing = plan.emailTo != null, emailTo = plan.emailTo))
            coroutineScope {
                if (plan.print) {
                    launch {
                        val printed = print(transaction)
                        progress.update { it.copy(reading = it.reading.copy(printing = false, printed = printed)) }
                    }
                }
                plan.emailTo?.let { to ->
                    launch {
                        val emailed = email(transaction, to)
                        progress.update { it.copy(reading = it.reading.copy(emailing = false, emailed = emailed)) }
                    }
                }
            }
            progress.update { it.copy(finished = true) }
        }
        return progress.transformWhile {
            emit(it.reading)
            !it.finished
        }
    }

    /** One automatic delivery's [reading], and whether it is the last one ([finished]). */
    private data class AutoProgress(
        val reading: AutoDelivery,
        val finished: Boolean = false,
    )

    /** Prints [copy] of the receipt of [transaction] (a refund has one copy), and says whether the merchant copy is due next. */
    suspend fun print(
        transaction: StoredTransaction,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): ReceiptPrint =
        when (transaction) {
            is StoredTransaction.Sale -> printSale(transaction.id, copy)
            is StoredTransaction.Refund -> ReceiptPrint(printRefund(transaction.id))
            is StoredTransaction.GiftCard -> ReceiptPrint(printGiftCard(transaction.id))
        }

    /**
     * Emails the receipt of [transaction] to [to]; a sale awaiting its payment link's payment gets the unpaid receipt as
     * a payment request, and a sale that was sent records the address.
     */
    suspend fun email(
        transaction: StoredTransaction,
        to: String,
    ): ActionResult =
        when (transaction) {
            is StoredTransaction.Sale -> emailSale(transaction.id, to)
            is StoredTransaction.Refund -> emailRefund(transaction.id, to)
            is StoredTransaction.GiftCard -> emailGiftCard(transaction.id, to)
        }

    private val documentSettings = settings.settings.map { it.receipt to it.languageTag }.distinctUntilChanged()
    private val emailSettings =
        settings.settings
            .map {
                it.email.isConfigured to it.payment.effectiveEmailCapture
            }.distinctUntilChanged()
    private val capabilities = status.state.map { it.printerAvailable to it.canShare }.distinctUntilChanged()

    private data class SaleDocuments(
        val sale: SaleEntity,
        val paper: ReceiptDocument,
        val electronic: ReceiptDocument,
    )

    private fun saleOffer(
        saleId: String,
        fresh: Boolean,
    ): Flow<ReceiptOffer> {
        val documents =
            combine(records.sales.observe(saleId), documentSettings) { record, (receipt, _) ->
                record?.let {
                    val paper = receipts.sale(it, receipt)
                    SaleDocuments(it.sale, paper, receipts.electronic(it, receipt, paper))
                }
            }.flowOn(cpu)
        return combine(documents, emailSettings, capabilities) { documents, (email, capture), (printer, share) ->
            ReceiptOffer(
                receipt = documents?.paper,
                canPrint = printer,
                canEmail = email && (!fresh || documents?.sale?.paymentLink == true || capture != EmailCapture.OFF),
                canShare = share,
                share =
                    documents?.takeIf { share }?.let {
                        val sale = it.sale
                        val standing = ReceiptStanding.of(sale)
                        SharedReceipt(
                            it.electronic,
                            sale.merchantReference,
                            sale.amountMinor,
                            sale.currency,
                            standing.unpaidLink,
                            standing.simulatedLink,
                        )
                    },
            )
        }
    }

    private fun refundOffer(refundId: String): Flow<ReceiptOffer> {
        val documents =
            combine(records.refunds.observe(refundId), documentSettings) { refund, (receipt, _) ->
                refund?.let { it to receipts.refund(it, receipt) }
            }.flowOn(cpu)
        return combine(documents, emailSettings, capabilities) { documents, (email, _), (printer, share) ->
            ReceiptOffer(
                receipt = documents?.second,
                canPrint = printer,
                canEmail = email,
                canShare = share,
                share =
                    documents?.takeIf { share }?.let { (refund, receipt) ->
                        SharedReceipt(receipt, refund.merchantReference, refund.amountMinor, refund.currency)
                    },
            )
        }
    }

    private fun giftCardOffer(groupId: String): Flow<ReceiptOffer> {
        val records = records.giftCards ?: return flowOf(ReceiptOffer(null, canPrint = false, canEmail = false))
        val documents =
            combine(records.observe(groupId), documentSettings) { activity, (receipt, _) ->
                activity?.let { it to receipts.giftCard(it, receipt) }
            }.flowOn(cpu)
        return combine(documents, emailSettings, capabilities) { documents, (email, _), (printer, share) ->
            ReceiptOffer(
                receipt = documents?.second,
                canPrint = printer,
                canEmail = email,
                canShare = share,
                share =
                    documents?.takeIf { share }?.let { (activity, receipt) ->
                        val main = activity.change ?: activity.stages.first()
                        SharedReceipt(receipt, main.merchantReference, main.movedMinor, activity.currency)
                    },
            )
        }
    }

    private suspend fun printGiftCard(groupId: String): ActionResult {
        val current = settings.current()
        val activity = records.giftCards?.activity(groupId) ?: return ActionResult.Missing
        return print(withContext(cpu) { receipts.giftCard(activity, current.receipt) }, current)
    }

    private suspend fun emailGiftCard(
        groupId: String,
        to: String,
    ): ActionResult {
        val activity = records.giftCards?.activity(groupId) ?: return ActionResult.Missing
        val document = withContext(cpu) { receipts.giftCard(activity, settings.current().receipt) }
        val main = activity.change ?: activity.stages.first()
        return emailer.send(to, document, main.merchantReference, ReceiptKind.GIFT_CARD)
    }

    /** Releases unclaimed automatic deliveries after confirmed complete history deletion. */
    internal fun forgetAll() = synchronized(armed) { armed.clear() }

    /** Forgets unclaimed automatic delivery only after these history IDs have been durably removed. */
    internal fun forget(ids: Set<String>) = synchronized(armed) { armed.removeAll(ids) }

    /** Makes the automatic delivery of [id], a just approved sale or accepted refund, due. */
    fun arm(id: String) = synchronized(armed) { armed += id }

    private data class DeliveryPlan(
        val print: Boolean = false,
        val emailTo: String? = null,
    )

    private suspend fun automationForSale(saleId: String): DeliveryPlan {
        val sale = records.sales.get(saleId)?.sale ?: return DeliveryPlan()
        // A split sale gets one automatic receipt with every tender, once nothing is left to pay.
        if (sale.isPartiallyPaid) return DeliveryPlan()
        val current = settings.current()
        return DeliveryPlan(
            print = printsAutomatically(current),
            emailTo = sale.shopperEmail?.takeIf { current.emailsReceiptAutomatically },
        )
    }

    private suspend fun printSale(
        saleId: String,
        copy: ReceiptCopy,
    ): ReceiptPrint {
        val current = settings.current()
        val record = records.sales.get(saleId) ?: return ReceiptPrint(ActionResult.Missing)
        val document = withContext(cpu) { receipts.sale(record, current.receipt, copy) }
        val result = print(document, current)
        val due = copy == ReceiptCopy.CUSTOMER && result == ActionResult.Success && merchantCopyWanted(record.sale, current)
        return ReceiptPrint(result, due)
    }

    private suspend fun printRefund(refundId: String): ActionResult {
        val current = settings.current()
        val refund = records.refunds.get(refundId) ?: return ActionResult.Missing
        return print(withContext(cpu) { receipts.refund(refund, current.receipt) }, current)
    }

    /** Prints [document], such as the sample receipt Settings prints to check the layout. */
    suspend fun printDocument(document: ReceiptDocument): ActionResult = print(document, settings.current())

    private suspend fun emailSale(
        saleId: String,
        to: String,
    ): ActionResult {
        val record = records.sales.get(saleId) ?: return ActionResult.Missing
        val sale = record.sale
        val document = withContext(cpu) { receipts.sale(record, settings.current().receipt, surface = ReceiptSurface.ELECTRONIC) }
        val result = emailer.send(to, document, sale.merchantReference, ReceiptKind.of(ReceiptStanding.of(sale)))
        return result.also { if (it == ActionResult.Success) records.sales.record(saleId, SaleEvent.Emailed(to.trim())) }
    }

    private suspend fun emailRefund(
        refundId: String,
        to: String,
    ): ActionResult {
        val refund = records.refunds.get(refundId) ?: return ActionResult.Missing
        val document = withContext(cpu) { receipts.refund(refund, settings.current().receipt) }
        return emailer.send(to, document, refund.merchantReference, ReceiptKind.of(refund))
    }

    /** Sends a short test email to [to], to check the SMTP settings. */
    suspend fun sendTestEmail(to: String): ActionResult = emailer.sendTest(to)

    private fun claim(id: String): Boolean = synchronized(armed) { armed.remove(id) }

    private fun printsAutomatically(current: AppSettings): Boolean = current.receipt.autoPrint && status.state.value.printerAvailable

    private fun merchantCopyWanted(
        sale: SaleEntity,
        current: AppSettings,
    ): Boolean =
        // An unpaid slip with a payment link is the shopper's way to pay; there is nothing to keep a copy of yet.
        !sale.awaitsLinkPayment &&
            when (current.receipt.merchantCopy) {
                MerchantCopyPolicy.NEVER -> false

                // The shopper signs the merchant copy of a receipt awaiting a tip.
                MerchantCopyPolicy.SIGNATURE_ONLY -> sale.signatureRequired || sale.standing == PaymentStanding.AWAITING_TIP

                MerchantCopyPolicy.ALWAYS -> true
            }

    private suspend fun print(
        document: ReceiptDocument,
        current: AppSettings,
    ): ActionResult =
        when (val attempt = gateway.print(withContext(cpu) { PrintRenderer.jobs(document, current.receipt.charsPerLine) })) {
            is Attempt.NotSetUp -> {
                ActionResult.Failed(Failure.NotSetUp(attempt.problem))
            }

            is Attempt.Made -> {
                when (val outcome = attempt.result) {
                    PrintOutcome.Printed -> ActionResult.Success
                    is PrintOutcome.Failed -> ActionResult.Failed(Failure.Remote(outcome.fault))
                }
            }
        }
}
