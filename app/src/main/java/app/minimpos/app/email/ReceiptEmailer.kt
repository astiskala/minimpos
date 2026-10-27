package app.minimpos.app.email

import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.receipt.ActionResult
import app.minimpos.app.receipt.ReceiptKind
import app.minimpos.core.logo.MonoPng
import app.minimpos.core.receipt.HtmlReceiptRenderer
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.shopper.ShopperReferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import javax.mail.MessagingException

/**
 * Localised texts of receipt emails.
 *
 * @property appName Used in the subject when no business name is set.
 * @property intro Paragraph above a sale receipt.
 * @property refundIntro Paragraph above a refund receipt.
 * @property testSubject Subject of the test email.
 * @property testBody Body of the test email.
 * @property preAuthIntro Paragraph above a pre-authorisation receipt.
 * @property cancellationIntro Paragraph above the receipt of a cancelled payment that only held its amount.
 * @property giftCardIntro Paragraph above a stored-value (gift-card) receipt.
 * @property linkSubject Subject of a payment link email, with the `{business}` and `{reference}` placeholders of
 *   [app.minimpos.app.data.settings.EmailSettings.subject].
 * @property linkIntro Paragraph above the unpaid receipt in a payment link email.
 * @property demoSubject Subject of an offline demo link or its receipt.
 * @property simulationNote Intro identifying a simulated link as non-payable.
 * @property cashoutIntro Paragraph above a cash-out receipt, without describing a merchandise purchase.
 */
data class EmailTexts(
    val appName: String,
    val intro: String,
    val refundIntro: String,
    val testSubject: String,
    val testBody: String,
    val preAuthIntro: String,
    val cancellationIntro: String,
    val giftCardIntro: String = "Here is your gift card receipt.",
    val linkSubject: String = "Payment request from {business}",
    val linkIntro: String = "Pay securely online with the link or the QR code below.",
    val demoSubject: String = "Demo link",
    val simulationNote: String = "Simulation only. No money moved.",
    val cashoutIntro: String = "Here is your cashout receipt.",
)

/**
 * Emails receipts as HTML with a plain-text alternative; the receipt's logo and QR codes are attached as inline PNG images. The
 * SMTP password is read from [SecretStore] for each email. Missing settings, invalid addresses and delivery errors are
 * returned as [ActionResult.Failed] with an [EmailFault] (and the mail server's reply, verbatim).
 */
class ReceiptEmailer(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val mailer: SmtpMailer,
    /** Renders QR code content as a PNG image. */
    private val qrPng: (String) -> ByteArray,
    /** Reads resources in the current app language at delivery time. */
    private val currentTexts: () -> EmailTexts,
    /** Dispatcher for QR PNGs and text rendering before SMTP delivery. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    private val texts: EmailTexts get() = currentTexts()

    /**
     * Emails [document], a receipt of [kind] with merchant reference [reference], to [to]. Its subject and introduction
     * follow the kind, as the document does: a payment link's request to pay gets the payment link subject, an offline
     * demo the demo subject and note, even after completion; others the receipt subject with their own introduction.
     */
    suspend fun send(
        to: String,
        document: ReceiptDocument,
        reference: String,
        kind: ReceiptKind,
    ): ActionResult {
        val current = settings.current()
        val words = texts
        val envelope =
            when (kind) {
                ReceiptKind.CASHOUT -> Envelope(words.cashoutIntro, current.email.subject)
                ReceiptKind.SALE -> Envelope(words.intro, current.email.subject)
                ReceiptKind.PRE_AUTHORISATION -> Envelope(words.preAuthIntro, current.email.subject)
                ReceiptKind.UNPAID_LINK -> Envelope(words.linkIntro, words.linkSubject)
                ReceiptKind.DEMO_LINK -> Envelope(words.simulationNote, words.demoSubject)
                ReceiptKind.REFUND -> Envelope(words.refundIntro, current.email.subject)
                ReceiptKind.CANCELLATION -> Envelope(words.cancellationIntro, current.email.subject)
                ReceiptKind.GIFT_CARD -> Envelope(words.giftCardIntro, current.email.subject)
            }
        return send(current, to, document, reference, envelope)
    }

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
        reference: String,
        envelope: Envelope,
    ): ActionResult {
        validate(current, to)?.let { return it }
        val business =
            current.receipt.businessName
                .trim()
                .ifEmpty { texts.appName }
        val subject =
            envelope.subjectFormat
                .replace("{business}", business)
                .replace("{reference}", reference)
        val intro = envelope.intro
        val message =
            withContext(cpu) {
                val images =
                    listOfNotNull(document.logo?.let { InlineImage(HtmlReceiptRenderer.LOGO_CID, MonoPng.encode(it)) }) +
                        document.qrCodes.mapIndexed { index, qr -> InlineImage("qr$index", qrPng(qr.content)) }
                EmailMessage(
                    to = to,
                    subject = subject,
                    html = HtmlReceiptRenderer().render(document, subject, intro),
                    text = intro + "\n\n" + PlainTextReceiptRenderer(current.receipt.charsPerLine).render(document),
                    images = images,
                )
            }
        return deliver(current, message)
    }

    private fun validate(
        current: AppSettings,
        to: String,
    ): ActionResult? {
        if (!current.email.isConfigured) return ActionResult.Failed(Failure.Email(EmailFault.NOT_CONFIGURED))
        if (!ShopperReferences.isValidEmail(to)) return ActionResult.Failed(Failure.Email(EmailFault.INVALID_ADDRESS))
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
            ActionResult.Failed(e.emailFailure())
        } catch (ignored: UnsupportedEncodingException) {
            // The sender name cannot be encoded: the server never got the message.
            ActionResult.Failed(Failure.Email(EmailFault.REJECTED))
        }

    /**
     * What a receipt email of one kind says around the receipt.
     *
     * @property intro The text above the receipt.
     * @property subjectFormat The subject, with its `{business}` and `{reference}` placeholders.
     */
    private class Envelope(
        val intro: String,
        val subjectFormat: String,
    )
}
