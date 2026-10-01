package io.minimpos.app.email

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.receipt.ActionResult
import io.minimpos.core.receipt.HtmlReceiptRenderer
import io.minimpos.core.receipt.PlainTextReceiptRenderer
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.shopper.ShopperReferences
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
 */
data class EmailTexts(
    val appName: String,
    val intro: String,
    val refundIntro: String,
    val testSubject: String,
    val testBody: String,
    val notConfigured: String,
    val invalidAddress: String,
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
    private val texts: EmailTexts,
    /** Renders QR code content as a PNG image. */
    private val qrPng: (String) -> ByteArray,
) {
    /** Emails [document], the receipt of the sale with merchant reference [reference], to [to]. */
    suspend fun sendSale(
        to: String,
        document: ReceiptDocument,
        reference: String,
    ): ActionResult = send(settings.current(), to, document, texts.intro, reference)

    /** Emails [document], the receipt of the refund with merchant reference [reference], to [to]. */
    suspend fun sendRefund(
        to: String,
        document: ReceiptDocument,
        reference: String,
    ): ActionResult = send(settings.current(), to, document, texts.refundIntro, reference)

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
    ): ActionResult {
        validate(current, to)?.let { return it }
        val business =
            current.receipt.businessName
                .trim()
                .ifEmpty { texts.appName }
        val subject =
            current.email.subject
                .replace("{business}", business)
                .replace("{reference}", reference)
        val images = document.qrCodes.mapIndexed { index, qr -> InlineImage("qr$index", qrPng(qr.content)) }
        val message =
            EmailMessage(
                to = to,
                subject = subject,
                html = HtmlReceiptRenderer().render(document, subject, intro),
                text = intro + "\n\n" + PlainTextReceiptRenderer(current.receipt.charsPerLine.coerceIn(24, 64)).render(document),
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
