package io.github.astiskala.minimpos.app.email

import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.receipt.ActionResult
import io.github.astiskala.minimpos.core.receipt.HtmlReceiptRenderer
import io.github.astiskala.minimpos.core.receipt.PlainTextReceiptRenderer
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.shopper.ShopperReferences
import java.io.UnsupportedEncodingException
import javax.mail.MessagingException

/**
 * Localised texts of receipt emails and their errors.
 *
 * @property appName Used in the subject when no business name is set.
 * @property intro Paragraph above a sale receipt.
 * @property refundIntro Paragraph above a refund receipt.
 * @property testSubject Subject of the test email.
 * @property testBody Body of the test email.
 * @property notConfigured Error when SMTP is not set up.
 * @property invalidAddress Error when the recipient address is not valid.
 * @property preAuthIntro Paragraph above a pre-authorisation receipt.
 * @property cancellationIntro Paragraph above the receipt of a cancelled payment that only held its amount.
 * @property linkSubject Subject of a payment link email, with the `{business}` and `{reference}` placeholders of
 *   [io.github.astiskala.minimpos.app.data.settings.EmailSettings.subject].
 * @property linkIntro Paragraph above the unpaid receipt in a payment link email.
 */
data class EmailTexts(
    val appName: String,
    val intro: String,
    val refundIntro: String,
    val testSubject: String,
    val testBody: String,
    val notConfigured: String,
    val invalidAddress: String,
    val preAuthIntro: String,
    val cancellationIntro: String,
    val linkSubject: String = "Payment request from {business}",
    val linkIntro: String = "Pay securely online with the link or the QR code below.",
)

/**
 * Emails receipts as HTML with a plain-text alternative; the receipt's QR codes are attached as inline PNG images. The
 * SMTP password is read from [SecretStore] for each email. Missing settings, invalid addresses and delivery errors are
 * returned as [ActionResult.Failure] with a message to show.
 */
class ReceiptEmailer(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val mailer: SmtpMailer,
    texts: EmailTexts,
    /** Renders QR code content as a PNG image. */
    private val qrPng: (String) -> ByteArray,
    /** Reads resources again after a device language change; defaults to the texts supplied at construction. */
    private val currentTexts: () -> EmailTexts = { texts },
) {
    private val texts: EmailTexts get() = currentTexts()

    /**
     * Emails [document], the receipt of the sale (or, with [preAuthorisation], the pre-authorisation) with merchant
     * reference [reference], to [to].
     */
    suspend fun sendSale(
        to: String,
        document: ReceiptDocument,
        reference: String,
        preAuthorisation: Boolean = false,
    ): ActionResult = send(settings.current(), to, document, if (preAuthorisation) texts.preAuthIntro else texts.intro, reference)

    /**
     * Emails [document], the unpaid receipt (with the payment link) of the sale with merchant reference [reference], to
     * [to], under the payment link subject rather than the receipt one.
     */
    suspend fun sendPaymentLink(
        to: String,
        document: ReceiptDocument,
        reference: String,
    ): ActionResult = send(settings.current(), to, document, texts.linkIntro, reference, texts.linkSubject)

    /**
     * Emails [document], the receipt of the refund (or, with [cancellation], of the cancelled hold) with
     * merchant reference [reference], to [to].
     */
    suspend fun sendRefund(
        to: String,
        document: ReceiptDocument,
        reference: String,
        cancellation: Boolean = false,
    ): ActionResult = send(settings.current(), to, document, if (cancellation) texts.cancellationIntro else texts.refundIntro, reference)

    /** Sends a short test email to [to], to check the SMTP settings. */
    suspend fun sendTest(to: String): ActionResult {
        val current = settings.current()
        validate(current, to)?.let { return it }
        val message = EmailMessage(to, texts.testSubject, "<p>${HtmlReceiptRenderer.escape(texts.testBody)}</p>", texts.testBody)
        return deliver(current, message)
    }

    private suspend fun send(
        current: AppSettings,
        to: String,
        document: ReceiptDocument,
        intro: String,
        reference: String,
        subjectFormat: String = current.email.subject,
    ): ActionResult {
        validate(current, to)?.let { return it }
        val business =
            current.receipt.businessName
                .trim()
                .ifEmpty { texts.appName }
        val subject =
            subjectFormat
                .replace("{business}", business)
                .replace("{reference}", reference)
        val images = document.qrCodes.mapIndexed { index, qr -> InlineImage("qr$index", qrPng(qr.content)) }
        val message =
            EmailMessage(
                to = to,
                subject = subject,
                html = HtmlReceiptRenderer().render(document, subject, intro),
                text = intro + "\n\n" + PlainTextReceiptRenderer(current.receipt.charsPerLine).render(document),
                images = images,
            )
        return deliver(current, message)
    }

    private fun validate(
        current: AppSettings,
        to: String,
    ): ActionResult? {
        if (!current.email.isConfigured) return ActionResult.Failure(texts.notConfigured)
        if (!ShopperReferences.isValidEmail(to)) return ActionResult.Failure(texts.invalidAddress)
        return null
    }

    private suspend fun deliver(
        current: AppSettings,
        message: EmailMessage,
    ): ActionResult =
        try {
            mailer.send(current.email, secrets.get(Secret.SMTP_PASSWORD), message)
            ActionResult.Success
        } catch (e: MessagingException) {
            ActionResult.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: UnsupportedEncodingException) {
            ActionResult.Failure(e.message ?: e.javaClass.simpleName)
        }
}
