package io.minimpos.app.email

import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.SmtpSecurity

/**
 * Email providers whose SMTP server Settings › Email can fill in, with the provider's own page on setting up SMTP.
 * All of them still accept a password (usually an app password) for SMTP, which is the only sign-in [SmtpMailer] has;
 * Outlook.com is missing because it takes only OAuth.
 *
 * @property label The provider's name, a brand that is not translated.
 * @property host The SMTP server [fill] puts in.
 * @property port The SMTP port [fill] puts in.
 * @property security How [fill] secures the connection.
 * @property helpUrl The provider's page on sending through its SMTP server from another app (an HTTPS URL), linked
 *   from Settings off a terminal and listed in the getting started guide.
 * @param hosts Every SMTP server of the provider, so [of] also recognises regional or plan-specific ones.
 */
enum class SmtpProvider(
    val label: String,
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val helpUrl: String,
    private val hosts: Regex = Regex(Regex.escape(host)),
) {
    /** Gmail and Google Workspace, with an app password (which needs 2-Step Verification). */
    GMAIL("Gmail", "smtp.gmail.com", SUBMISSION, SmtpSecurity.STARTTLS, "https://support.google.com/mail/answer/185833"),

    /** Microsoft 365 (Office 365) business mailboxes, while the admin allows SMTP AUTH with a password. */
    MICROSOFT_365(
        "Microsoft 365",
        "smtp.office365.com",
        SUBMISSION,
        SmtpSecurity.STARTTLS,
        "https://learn.microsoft.com/en-us/exchange/mail-flow-best-practices/" +
            "how-to-set-up-a-multifunction-device-or-application-to-send-email-using-microsoft-365-or-office-365",
    ),

    /** iCloud Mail, with an app-specific password. */
    ICLOUD("iCloud Mail", "smtp.mail.me.com", SUBMISSION, SmtpSecurity.STARTTLS, "https://support.apple.com/en-us/102525"),

    /** Yahoo Mail, with an app password. */
    YAHOO(
        "Yahoo Mail",
        "smtp.mail.yahoo.com",
        SMTPS,
        SmtpSecurity.SSL,
        "https://help.yahoo.com/kb/pop-smtp-settings-article-sln4724.html",
    ),

    /** Fastmail, with an app password (not on its Basic plan). */
    FASTMAIL(
        "Fastmail",
        "smtp.fastmail.com",
        SMTPS,
        SmtpSecurity.SSL,
        "https://www.fastmail.help/hc/en-us/articles/1500000278342-Server-names-and-ports",
    ),

    /**
     * Zoho Mail: [host] is for personal and free accounts in the US data centre; paid plans use `smtppro`, other data
     * centres their own domain (such as `smtp.zoho.eu`).
     */
    ZOHO(
        "Zoho Mail",
        "smtp.zoho.com",
        SMTPS,
        SmtpSecurity.SSL,
        "https://www.zoho.com/mail/help/zoho-smtp.html",
        Regex("smtp(pro)?\\.zoho\\.[a-z.]+"),
    ),

    /** Proton Mail, with an SMTP token for an address on the merchant's own domain (paid plans). */
    PROTON("Proton Mail", "smtp.protonmail.ch", SUBMISSION, SmtpSecurity.STARTTLS, "https://proton.me/support/smtp-submission"),
    ;

    /** [settings] with this provider's server, port and security; the login and sender are left as they are. */
    fun fill(settings: EmailSettings): EmailSettings = settings.copy(host = host, port = port, security = security)

    /** Finding the provider of a server, and making way for a server of none of them. */
    companion object {
        /** The provider whose SMTP server [host] is (ignoring case and surrounding spaces); null for any other. */
        fun of(host: String): SmtpProvider? {
            val name = host.trim().lowercase()
            return entries.firstOrNull { it.hosts.matches(name) }
        }

        /**
         * [settings] ready for a server of no listed provider ("Other"): a provider's server is cleared, with the
         * default port and security; a server of no provider stays as it is. The login and sender are left alone.
         */
        fun other(settings: EmailSettings): EmailSettings =
            if (of(settings.host) == null) {
                settings
            } else {
                EmailSettings().let { settings.copy(host = it.host, port = it.port, security = it.security) }
            }
    }
}

/** The message submission port, secured with STARTTLS. */
private const val SUBMISSION = 587

/** The SMTPS port, secured with TLS from the start. */
private const val SMTPS = 465
