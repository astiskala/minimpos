package app.minimpos.app.email

import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.SmtpSecurity
import app.minimpos.terminal.transport.ExternalText
import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.AuthenticationFailedException
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * An image embedded in the HTML part of an email, referenced from it as `cid:<contentId>`.
 *
 * @property contentId The Content-ID, such as "qr0"; also used for the attachment's file name.
 * @property png The image as PNG bytes.
 */
class InlineImage(
    val contentId: String,
    val png: ByteArray,
)

/**
 * One email to send.
 *
 * @property to Recipient address (or comma-separated addresses).
 * @property subject Subject line, already filled in.
 * @property html The HTML body, shown by most mail apps.
 * @property text The plain-text alternative.
 * @property images Images the HTML refers to by Content-ID.
 */
data class EmailMessage(
    val to: String,
    val subject: String,
    val html: String,
    val text: String,
    val images: List<InlineImage> = emptyList(),
)

/** Delivers a built message; tests capture messages instead of sending them. */
fun interface MailTransport {
    /**
     * Sends [message], blocking until the server accepted it.
     *
     * @throws javax.mail.MessagingException if it could not be delivered.
     */
    fun send(message: MimeMessage)
}

/** Sends HTML emails (with a plain-text alternative and inline images) over SMTP using JavaMail for Android. */
class SmtpMailer(
    private val transport: MailTransport = MailTransport { Transport.send(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Builds and sends [message] with [config] and the SMTP [password] (null when there is none), on the IO
     * dispatcher. Connecting, reading and writing each time out after 20 seconds.
     *
     * @throws javax.mail.MessagingException if an address is invalid or the message could not be delivered.
     * @throws java.io.UnsupportedEncodingException if the sender name cannot be encoded.
     */
    suspend fun send(
        config: EmailSettings,
        password: String?,
        message: EmailMessage,
    ) = withContext(io) { transport.send(build(config, password, message)) }

    /**
     * Builds the MIME message for [message] without sending it: `multipart/alternative` (text and HTML), wrapped in
     * `multipart/related` with the images when there are any. The server's certificate host name is checked with
     * STARTTLS and SSL.
     *
     * @throws javax.mail.MessagingException if an address is invalid.
     * @throws java.io.UnsupportedEncodingException if the sender name cannot be encoded.
     */
    fun build(
        config: EmailSettings,
        password: String?,
        message: EmailMessage,
    ): MimeMessage {
        val authenticate = config.username.isNotBlank()
        val authenticator =
            if (authenticate) {
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(config.username.trim(), password.orEmpty())
                }
            } else {
                null
            }
        val session = Session.getInstance(properties(config, authenticate), authenticator)
        return MimeMessage(session).apply {
            setFrom(InternetAddress(config.fromAddress.trim(), config.fromName.trim().ifEmpty { null }, CHARSET))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to.trim(), true))
            config.bcc
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { setRecipients(Message.RecipientType.BCC, InternetAddress.parse(it, true)) }
            setSubject(message.subject, CHARSET)
            sentDate = Date()
            setContent(mimeBody(message))
            saveChanges()
        }
    }

    private fun properties(
        config: EmailSettings,
        authenticate: Boolean,
    ) = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", config.host.trim())
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.auth", authenticate.toString())
        put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS)
        put("mail.smtp.timeout", TIMEOUT_MILLIS)
        put("mail.smtp.writetimeout", TIMEOUT_MILLIS)
        when (config.security) {
            SmtpSecurity.STARTTLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }

            SmtpSecurity.SSL -> {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }

            SmtpSecurity.NONE -> {}
        }
    }

    private fun mimeBody(message: EmailMessage): MimeMultipart {
        val alternative =
            MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(message.text, CHARSET) })
                addBodyPart(MimeBodyPart().apply { setContent(message.html, "text/html; charset=$CHARSET") })
            }
        if (message.images.isEmpty()) return alternative
        return MimeMultipart("related").apply {
            addBodyPart(MimeBodyPart().apply { setContent(alternative) })
            message.images.forEach { image ->
                addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(image.png, "image/png"))
                        setHeader("Content-ID", "<${image.contentId}>")
                        disposition = MimeBodyPart.INLINE
                        fileName = "${image.contentId}.png"
                    },
                )
            }
        }
    }

    private companion object {
        const val CHARSET = "UTF-8"
        const val TIMEOUT_MILLIS = "20000"
    }
}

/**
 * Why this exception kept an email from being sent: the server refused the login ([EmailFault.AUTHENTICATION]), the
 * message or a recipient ([EmailFault.REJECTED]), an address was invalid ([EmailFault.INVALID_ADDRESS]), or the server
 * could not be reached ([EmailFault.UNREACHABLE]). JavaMail keeps the mail server's own reply only as the text of its
 * SMTP exceptions; that reply is kept verbatim, while JavaMail's own words are never shown.
 */
internal fun MessagingException.emailFailure(): Failure.Email {
    val chain = generateSequence<Exception>(this) { (it as? MessagingException)?.nextException }.take(MAX_CHAIN).toList()
    val reply =
        chain.firstOrNull {
            it is AuthenticationFailedException || it is SMTPSendFailedException || it is SMTPAddressFailedException
        }
    val fault =
        when {
            chain.any { it is AuthenticationFailedException } -> EmailFault.AUTHENTICATION
            chain.any { it is AddressException } -> EmailFault.INVALID_ADDRESS
            chain.any { it is SendFailedException } -> EmailFault.REJECTED
            else -> EmailFault.UNREACHABLE
        }
    return Failure.Email(fault, ExternalText.of(reply?.message?.replace(WHITESPACE, " ")))
}

/** How deep [MessagingException.getNextException] is followed. */
private const val MAX_CHAIN = 8

private val WHITESPACE = Regex("\\s+")
