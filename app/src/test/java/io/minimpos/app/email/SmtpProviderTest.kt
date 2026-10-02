package io.minimpos.app.email

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.SmtpSecurity
import org.junit.Test

/** What the email provider buttons fill in, and recognising a provider from its server. */
class SmtpProviderTest {
    @Test
    fun `a provider fills in its server, port and security, and leaves the login and sender alone`() {
        val typed =
            EmailSettings(host = "mail.example.com", port = 25, security = SmtpSecurity.NONE, username = "me", fromAddress = "a@b.co")
        assertThat(SmtpProvider.YAHOO.fill(typed))
            .isEqualTo(typed.copy(host = "smtp.mail.yahoo.com", port = 465, security = SmtpSecurity.SSL))
        assertThat(SmtpProvider.GMAIL.fill(typed))
            .isEqualTo(typed.copy(host = "smtp.gmail.com", port = 587, security = SmtpSecurity.STARTTLS))
    }

    @Test
    fun `Other clears a provider's server for one typed by hand, and keeps a server of no provider`() {
        val gmail = SmtpProvider.GMAIL.fill(EmailSettings(security = SmtpSecurity.SSL, port = 465, username = "me", fromAddress = "a@b.co"))
        assertThat(SmtpProvider.other(SmtpProvider.YAHOO.fill(gmail)))
            .isEqualTo(gmail.copy(host = "", port = 587, security = SmtpSecurity.STARTTLS))
        val own = EmailSettings(host = "mail.example.com", port = 2525, security = SmtpSecurity.SSL, username = "me")
        assertThat(SmtpProvider.other(own)).isEqualTo(own)
        assertThat(SmtpProvider.other(EmailSettings())).isEqualTo(EmailSettings())
    }

    @Test
    fun `every provider is recognised from the server it fills in, and points to an HTTPS help page`() {
        SmtpProvider.entries.forEach {
            assertThat(SmtpProvider.of(it.fill(EmailSettings()).host)).isEqualTo(it)
            assertThat(it.helpUrl).startsWith("https://")
        }
        assertThat(SmtpProvider.entries.map { it.label }).doesNotContain("Outlook.com")
    }

    @Test
    fun `servers are matched ignoring case and spaces, including Zoho's other plans and data centres`() {
        assertThat(SmtpProvider.of(" SMTP.Office365.com ")).isEqualTo(SmtpProvider.MICROSOFT_365)
        listOf("smtppro.zoho.com", "smtp.zoho.eu", "smtppro.zoho.com.au").forEach {
            assertThat(SmtpProvider.of(it)).isEqualTo(SmtpProvider.ZOHO)
        }
        listOf("", "mail.example.com", "smtpxgmail.com", "smtp.gmail.com.evil.example").forEach {
            assertThat(SmtpProvider.of(it)).isNull()
        }
    }
}
