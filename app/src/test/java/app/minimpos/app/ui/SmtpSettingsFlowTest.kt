package app.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.SmtpSecurity
import app.minimpos.app.email.SmtpProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Settings › Email opened in [env], with a login typed but no server. */
@OptIn(ExperimentalTestApi::class)
open class SmtpSettingsScreen(
    @get:Rule(order = 0) val env: TestEnvironment,
) {
    @get:Rule(order = 1)
    val compose = createComposeRule()

    protected val container = env.container

    @Before
    fun setUp() {
        env.useSimulator { it.copy(email = EmailSettings(username = "cafe@example.com")) }
        await { container.terminalStatus.state.first { it.loaded } }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_email")
        compose.onNodeWithTag("section_email").performScrollTo().performClick()
        waitForTag("smtpProvider_GMAIL")
    }

    protected fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    protected fun email() = container.settingsState.value.email
}

/** On a phone or tablet: filling in a provider's SMTP server and opening its help page. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class SmtpSettingsFlowTest : SmtpSettingsScreen(TestEnvironment()) {
    @Test
    fun `a provider button fills in its server, port and security, and the fields show them`() {
        compose.onNodeWithTag("smtpAdvice").assertDoesNotExist()
        compose.onNodeWithTag("smtpProvider_YAHOO").performClick()
        compose.awaitCondition("Yahoo's server is stored") { email().host == "smtp.mail.yahoo.com" }
        assertThat(
            email(),
        ).isEqualTo(EmailSettings(host = "smtp.mail.yahoo.com", port = 465, security = SmtpSecurity.SSL, username = "cafe@example.com"))
        compose.waitUntilAtLeastOneExists(hasTestTag("smtpHost") and hasText("smtp.mail.yahoo.com"), 15_000)
        compose.onNodeWithTag("smtpPort").assertTextContains("465")
        compose.onNodeWithTag("smtpSecurity").assertTextContains("SSL/TLS", substring = true)
        compose.onNodeWithTag("smtpAdvice").assertTextContains("app password", substring = true)

        // Another provider replaces it; typing a server of no known provider takes the advice away.
        compose.onNodeWithTag("smtpProvider_MICROSOFT_365").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("smtpHost") and hasText("smtp.office365.com"), 15_000)
        assertThat(email().port).isEqualTo(587)
        assertThat(email().security).isEqualTo(SmtpSecurity.STARTTLS)
        compose.onNodeWithTag("smtpAdvice").assertTextContains("SMTP AUTH", substring = true)
        compose.onNodeWithTag("smtpHost").performTextReplacement("mail.example.com")
        compose.awaitCondition("the typed server is stored") { email().host == "mail.example.com" }
        compose.onNodeWithTag("smtpAdvice").assertDoesNotExist()
        compose.onNodeWithTag("smtpHelp").assertDoesNotExist()
    }

    @Test
    fun `Other clears a provider's server for one typed by hand, and stays chosen for it`() {
        compose.onNodeWithTag("smtpProvider_OTHER").assertIsNotSelected()
        compose.onNodeWithTag("smtpProvider_YAHOO").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("smtpHost") and hasText("smtp.mail.yahoo.com"), 15_000)
        compose.onNodeWithTag("smtpProvider_OTHER").performClick()
        compose.awaitCondition("Yahoo's server is cleared") { email().host.isEmpty() }
        assertThat(email()).isEqualTo(EmailSettings(username = "cafe@example.com"))
        compose.waitUntilAtLeastOneExists(hasTestTag("smtpPort") and hasText("587"), 15_000)
        compose.onNodeWithTag("smtpProvider_OTHER").assertIsSelected()
        compose.onNodeWithTag("smtpProvider_YAHOO").assertIsNotSelected()
        compose.onNodeWithTag("smtpAdvice").assertDoesNotExist()

        compose.onNodeWithTag("smtpHost").performTextReplacement("mail.example.com")
        compose.awaitCondition("the typed server is stored") { email().host == "mail.example.com" }
        compose.onNodeWithTag("smtpProvider_OTHER").assertIsSelected()
        // Choosing Other again keeps a server typed by hand.
        compose.onNodeWithTag("smtpProvider_OTHER").performClick()
        compose.onNodeWithTag("smtpHost").assertTextContains("mail.example.com")
        assertThat(email().host).isEqualTo("mail.example.com")
    }

    @Test
    fun `the help button opens the provider's page in a browser`() {
        compose.onNodeWithTag("smtpProvider_ICLOUD").performClick()
        waitForTag("smtpHelp")
        compose.onNodeWithTag("smtpHelp").assertTextContains("How to set up iCloud Mail").performClick()
        val opened = checkNotNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertThat(opened.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(opened.dataString).isEqualTo(SmtpProvider.ICLOUD.helpUrl)
    }
}

/** On an Adyen terminal, which has no browser: the provider's advice, but no link to its help page. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SmtpSettingsOnTerminalTest : SmtpSettingsScreen(TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), FakeTerminal())) {
    @Test
    fun `a provider button fills in its server and says what it needs, without a help link`() {
        assertThat(container.terminalStatus.state.value.onTerminal).isTrue()
        compose.onNodeWithTag("smtpProvider_GMAIL").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("smtpHost") and hasText("smtp.gmail.com"), 15_000)
        compose.onNodeWithTag("smtpAdvice").assertTextContains("app password", substring = true)
        compose.onNodeWithTag("smtpHelp").assertDoesNotExist()
    }
}
