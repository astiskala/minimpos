package io.github.astiskala.minimpos.app.payment

import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.repo.RefundRepository
import io.github.astiskala.minimpos.app.data.repo.SaleEvent
import io.github.astiskala.minimpos.app.data.repo.SaleRepository
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.EmailCapture
import io.github.astiskala.minimpos.app.data.settings.MerchantCopyPolicy
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.email.ReceiptEmailer
import io.github.astiskala.minimpos.app.receipt.ActionResult
import io.github.astiskala.minimpos.app.receipt.PrintRenderer
import io.github.astiskala.minimpos.app.receipt.ReceiptFactory
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.ReceiptStanding
import io.github.astiskala.minimpos.app.refund.awaitsLinkPayment
import io.github.astiskala.minimpos.app.refund.standing
import io.github.astiskala.minimpos.app.terminal.Attempt
import io.github.astiskala.minimpos.app.terminal.TerminalGateway
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
import io.github.astiskala.minimpos.core.receipt.ReceiptCopy
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.terminal.client.PrintOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * A stored transaction whose receipt is delivered, see [ReceiptDelivery]: a sale (taken on a terminal or through a
 * payment link) or a refund (or cancellation).
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
 * @property share The receipt to share when asked, as it is emailed; null while the transaction is not stored.
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
 * What to deliver automatically after a transaction, see [ReceiptDelivery.automation].
 *
 * @property print Whether to print the customer receipt.
 * @property emailTo Where to email the receipt, or null for no email.
 */
data class AutoDelivery(
    val print: Boolean = false,
    val emailTo: String? = null,
)

/**
 * Receipts of stored sales and refunds, delivered by printing on the terminal (or the simulator's on-screen printer),
 * by email and, off-terminal, through Android's share sheet (the screen shares [ReceiptOffer.share]), with the current
 * receipt settings, and what the screens offer for them ([offer]). Every operation takes the [StoredTransaction], and
 * what differs between sales and refunds is decided here: a sale has a merchant copy, a refund one copy only; the
 * receipt of a sale still awaiting its payment link's payment is an unpaid one with the link, emailed as a payment
 * request. It decides what is delivered automatically after a transaction, exactly once per transaction that
 * succeeded while the app runs: the transaction lifecycles call [arm], and [io.github.astiskala.minimpos.app.feature.TransactionActions]
 * claims the automation for a transaction just made with [automation], which only delivers something the first time.
 *
 * Missing sales and refunds (for example after pruning), missing email setup and delivery errors are returned as
 * [ActionResult.Failure] with a message to show; missing terminal setup as [ActionResult.NotSetUp].
 *
 * @param settings The receipt, payment and email settings, read for each call.
 * @param sales Stored sales; an emailed sale records the address.
 * @param refunds Stored refunds.
 * @param receipts Builds the receipt documents.
 * @param gateway Prints them.
 * @param status Tells whether printing is offered.
 * @param emailer Emails them.
 * @param notFound The message when the sale or refund no longer exists.
 */
class ReceiptDelivery(
    private val settings: SettingsRepository,
    private val sales: SaleRepository,
    private val refunds: RefundRepository,
    private val receipts: ReceiptFactory,
    private val gateway: TerminalGateway,
    private val status: TerminalStatus,
    private val emailer: ReceiptEmailer,
    private val notFound: String,
) {
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
        }

    /**
     * What to deliver automatically for [transaction]: nothing unless it was armed and not claimed before; else the
     * customer receipt is printed when Settings › Receipts › Print automatically is on and printing is offered, and a
     * sale's is emailed when the email was captured before payment and Send automatically is on.
     */
    suspend fun automation(transaction: StoredTransaction): AutoDelivery =
        when (transaction) {
            is StoredTransaction.Sale -> automationForSale(transaction.id)
            is StoredTransaction.Refund -> AutoDelivery(print = claim(transaction.id) && printsAutomatically(settings.current()))
        }

    /** Prints [copy] of the receipt of [transaction] (a refund has one copy), and says whether the merchant copy is due next. */
    suspend fun print(
        transaction: StoredTransaction,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): ReceiptPrint =
        when (transaction) {
            is StoredTransaction.Sale -> printSale(transaction.id, copy)
            is StoredTransaction.Refund -> ReceiptPrint(printRefund(transaction.id))
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
        }

    private fun saleOffer(
        saleId: String,
        fresh: Boolean,
    ): Flow<ReceiptOffer> =
        combine(sales.observe(saleId), settings.settings, status.state) { record, current, terminal ->
            val link = record?.sale?.paymentLink == true
            val captured = !fresh || link || current.payment.effectiveEmailCapture != EmailCapture.OFF
            ReceiptOffer(
                receipt = record?.let { receipts.sale(it, current.receipt) },
                canPrint = terminal.printerAvailable,
                canEmail = current.email.isConfigured && captured,
                canShare = terminal.canShare,
                share =
                    record?.let {
                        val sale = it.sale
                        val standing = ReceiptStanding.of(sale)
                        SharedReceipt(
                            document = receipts.sale(it, current.receipt, paper = false),
                            reference = sale.merchantReference,
                            amountMinor = sale.amountMinor,
                            currency = sale.currency,
                            paymentLink = standing.unpaidLink,
                            simulatedLink = standing.simulatedLink,
                        )
                    },
            )
        }

    private fun refundOffer(refundId: String): Flow<ReceiptOffer> =
        combine(refunds.observe(refundId), settings.settings, status.state) { refund, current, terminal ->
            val receipt = refund?.let { receipts.refund(it, current.receipt) }
            ReceiptOffer(
                receipt = receipt,
                canPrint = terminal.printerAvailable,
                canEmail = current.email.isConfigured,
                canShare = terminal.canShare,
                share =
                    refund?.let { stored ->
                        receipt?.let { SharedReceipt(it, stored.merchantReference, stored.amountMinor, stored.currency) }
                    },
            )
        }

    /** Makes the automatic delivery of [id], a just approved sale or accepted refund, due. */
    fun arm(id: String) = synchronized(armed) { armed += id }

    private suspend fun automationForSale(saleId: String): AutoDelivery {
        val sale = (if (claim(saleId)) sales.get(saleId)?.sale else null) ?: return AutoDelivery()
        val current = settings.current()
        val payment = current.payment
        return AutoDelivery(
            print = printsAutomatically(current),
            emailTo = sale.shopperEmail?.takeIf { payment.captureEmailBefore && payment.autoSendEmail },
        )
    }

    private suspend fun printSale(
        saleId: String,
        copy: ReceiptCopy,
    ): ReceiptPrint {
        val current = settings.current()
        val record = sales.get(saleId) ?: return ReceiptPrint(ActionResult.Failure(notFound))
        val result = print(receipts.sale(record, current.receipt, copy), current)
        val due = copy == ReceiptCopy.CUSTOMER && result == ActionResult.Success && merchantCopyWanted(record.sale, current)
        return ReceiptPrint(result, due)
    }

    private suspend fun printRefund(refundId: String): ActionResult {
        val current = settings.current()
        val refund = refunds.get(refundId) ?: return ActionResult.Failure(notFound)
        return print(receipts.refund(refund, current.receipt), current)
    }

    /** Prints [document], such as the sample receipt Settings prints to check the layout. */
    suspend fun printDocument(document: ReceiptDocument): ActionResult = print(document, settings.current())

    private suspend fun emailSale(
        saleId: String,
        to: String,
    ): ActionResult {
        val record = sales.get(saleId) ?: return ActionResult.Failure(notFound)
        val sale = record.sale
        val document = receipts.sale(record, settings.current().receipt, paper = false)
        val standing = ReceiptStanding.of(sale)
        val result =
            if (standing.unpaidLink != null) {
                emailer.sendPaymentLink(to, document, sale.merchantReference, standing.simulatedLink)
            } else {
                emailer.sendSale(to, document, sale.merchantReference, standing.preAuthorisation, standing.simulatedLink)
            }
        return result.also { if (it == ActionResult.Success) sales.record(saleId, SaleEvent.Emailed(to.trim())) }
    }

    private suspend fun emailRefund(
        refundId: String,
        to: String,
    ): ActionResult {
        val refund = refunds.get(refundId) ?: return ActionResult.Failure(notFound)
        return emailer.sendRefund(to, receipts.refund(refund, settings.current().receipt), refund.merchantReference, refund.cancellation)
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
        when (val attempt = gateway.print(PrintRenderer.jobs(document, current.receipt.charsPerLine))) {
            is Attempt.NotSetUp -> {
                ActionResult.NotSetUp(attempt.problem)
            }

            is Attempt.Made -> {
                when (val outcome = attempt.result) {
                    PrintOutcome.Printed -> ActionResult.Success
                    is PrintOutcome.Failed -> ActionResult.Failure(outcome.message)
                }
            }
        }
}
