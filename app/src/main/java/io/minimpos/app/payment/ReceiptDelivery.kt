package io.minimpos.app.payment

import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.repo.RefundRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.MerchantCopyPolicy
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.email.ReceiptEmailer
import io.minimpos.app.receipt.ActionResult
import io.minimpos.app.receipt.PrintRenderer
import io.minimpos.app.receipt.ReceiptFactory
import io.minimpos.app.terminal.TerminalGateway
import io.minimpos.app.terminal.TerminalStatus
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.terminal.client.PrintOutcome

/**
 * The result of printing a copy of a sale's receipt.
 *
 * @property result Whether it printed.
 * @property merchantCopyDue Whether the merchant copy should be printed next: the customer copy printed and the merchant
 *   copy setting asks for one (always, or when the shopper had to sign).
 */
data class SalePrint(
    val result: ActionResult,
    val merchantCopyDue: Boolean = false,
)

/**
 * What to deliver automatically after a transaction, see [ReceiptDelivery.automationForSale].
 *
 * @property print Whether to print the customer receipt.
 * @property emailTo Where to email the receipt, or null for no email.
 */
data class AutoDelivery(
    val print: Boolean = false,
    val emailTo: String? = null,
)

/**
 * Receipts of stored sales and refunds, delivered by printing on the terminal (or the simulator's on-screen printer)
 * and by email, with the current receipt settings. It also decides what is delivered automatically after a
 * transaction, exactly once per transaction that succeeded while the app runs: the transaction lifecycles call [arm],
 * and the result screens claim the automation with [automationForSale] and [automationForRefund], which only deliver
 * something the first time.
 *
 * Missing sales and refunds (for example after pruning), missing terminal or email setup and delivery errors are
 * returned as [ActionResult.Failure] with a message to show.
 *
 * @param settings The receipt, payment and email settings, read for each call.
 * @param sales Stored sales; an emailed sale records the address.
 * @param refunds Stored refunds.
 * @param receipts Builds the receipt documents.
 * @param gateway Prints them.
 * @param status Tells whether printing is offered, for automatic printing.
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

    /** Makes the automatic delivery of [id], a just approved sale or accepted refund, due. */
    fun arm(id: String) = synchronized(armed) { armed += id }

    /**
     * What to deliver automatically for sale [saleId]: nothing unless it was armed and not claimed before; else the
     * customer receipt is printed when Settings › Receipts › Print automatically is on and printing is offered, and
     * emailed when the email was captured before payment and Send automatically is on.
     */
    suspend fun automationForSale(saleId: String): AutoDelivery {
        val sale = (if (claim(saleId)) sales.get(saleId)?.sale else null) ?: return AutoDelivery()
        val current = settings.current()
        val payment = current.payment
        return AutoDelivery(
            print = printsAutomatically(current),
            emailTo = sale.shopperEmail?.takeIf { payment.captureEmailBefore && payment.autoSendEmail },
        )
    }

    /** What to deliver automatically for refund [refundId]: as for [automationForSale], but only printing. */
    suspend fun automationForRefund(refundId: String): AutoDelivery =
        AutoDelivery(print = claim(refundId) && printsAutomatically(settings.current()))

    /** Prints [copy] of the receipt of sale [saleId], and says whether the merchant copy is due next. */
    suspend fun printSale(
        saleId: String,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): SalePrint {
        val current = settings.current()
        val record = sales.get(saleId) ?: return SalePrint(ActionResult.Failure(notFound))
        val result = print(receipts.sale(record, current.receipt, copy), current)
        val due = copy == ReceiptCopy.CUSTOMER && result == ActionResult.Success && merchantCopyWanted(record.sale, current)
        return SalePrint(result, due)
    }

    /** Prints the receipt of refund [refundId]. */
    suspend fun printRefund(refundId: String): ActionResult {
        val current = settings.current()
        val refund = refunds.get(refundId) ?: return ActionResult.Failure(notFound)
        return print(receipts.refund(refund, current.receipt), current)
    }

    /** Prints [document], such as a sample receipt to check the layout. */
    suspend fun printDocument(document: ReceiptDocument): ActionResult = print(document, settings.current())

    /** Emails the receipt of sale [saleId] to [to] and, when it was sent, records the address on the sale. */
    suspend fun emailSale(
        saleId: String,
        to: String,
    ): ActionResult {
        val record = sales.get(saleId) ?: return ActionResult.Failure(notFound)
        val document = receipts.sale(record, settings.current().receipt)
        return emailer.sendSale(to, document, record.sale.merchantReference).also {
            if (it == ActionResult.Success) sales.markEmailed(saleId, to.trim())
        }
    }

    /** Emails the receipt of refund [refundId] to [to]. */
    suspend fun emailRefund(
        refundId: String,
        to: String,
    ): ActionResult {
        val refund = refunds.get(refundId) ?: return ActionResult.Failure(notFound)
        return emailer.sendRefund(to, receipts.refund(refund, settings.current().receipt), refund.merchantReference)
    }

    /** Sends a short test email to [to], to check the SMTP settings. */
    suspend fun sendTestEmail(to: String): ActionResult = emailer.sendTest(to)

    private fun claim(id: String): Boolean = synchronized(armed) { armed.remove(id) }

    private fun printsAutomatically(current: AppSettings): Boolean = current.receipt.autoPrint && status.state.value.printerAvailable

    private fun merchantCopyWanted(
        sale: SaleEntity,
        current: AppSettings,
    ): Boolean =
        when (current.receipt.merchantCopy) {
            MerchantCopyPolicy.NEVER -> false
            MerchantCopyPolicy.SIGNATURE_ONLY -> sale.signatureRequired
            MerchantCopyPolicy.ALWAYS -> true
        }

    private suspend fun print(
        document: ReceiptDocument,
        current: AppSettings,
    ): ActionResult =
        when (val outcome = gateway.print(PrintRenderer.jobs(document, current.receipt.charsPerLine))) {
            PrintOutcome.Printed -> ActionResult.Success
            is PrintOutcome.Failed -> ActionResult.Failure(outcome.message)
        }
}
