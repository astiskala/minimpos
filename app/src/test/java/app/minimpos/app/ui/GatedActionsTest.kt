package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Actions whose precondition is known before they are pressed are disabled, with the fix shown under them; those that
 * wait for payments to be set up explain that instead, and content only meaningful once set up is replaced by the way there.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class GatedActionsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private fun open(section: String) {
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_$section"), 15_000)
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
    }

    private fun assertBlocked(
        tag: String,
        reason: Int,
    ) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)
        compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        compose.waitUntilAtLeastOneExists(hasTestTag("blockedReason"), 15_000)
        compose.onNodeWithTag("blockedReason").performScrollTo().assertTextContains(env.context.getString(reason))
    }

    private fun useNetworkTerminalWithoutId() {
        env.updateSettings {
            it.copy(
                terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST, host = "192.0.2.10"),
            )
        }
        env.container.start()
    }

    @Test
    fun `scanned wallets wait for payments to be set up`() {
        useNetworkTerminalWithoutId()
        open("terminal")
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
        compose.onNodeWithTag("refreshWallets").assertDoesNotExist()
    }

    @Test
    fun `capabilities wait for payments to be set up and lead to the setup`() {
        useNetworkTerminalWithoutId()
        open("capabilities")
        compose.waitUntilAtLeastOneExists(hasTestTag("setupRequired"), 15_000)
        listOf("refreshCapabilities", "cap_tipping", "saveCapDcc").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("finishSetup").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
    }

    @Test
    fun `test receipts wait for a printer`() {
        env.useSimulator()
        env.updateSettings { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
        open("receipts")
        assertBlocked("printTest", R.string.setup_printer)
        env.updateSettings { it.copy(simulator = it.simulator.copy(hasPrinter = true)) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("blockedReason").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("printTest").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `reconciliation from Home checks the simulator's totals and waits for the terminal ID elsewhere`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("reconciliation").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("reconcileTerminal"), 15_000)
        compose.onNodeWithTag("reconcileTerminal").assertIsEnabled().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("reconciliationCard"), 15_000)
        compose.onNodeWithTag("reconciliationStatus").assertTextContains(env.context.getString(R.string.reconciliation_matched))
        // Closing the period is confirmed first, then ends it: it cannot be closed twice.
        compose
            .onNodeWithTag("closeReconciliationPeriod")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirm"), 15_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("reconciliationCard").fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag("closeReconciliationPeriod").assertIsNotEnabled() }.isSuccess
        }
        // Elsewhere the check waits for payments to be set up: pressing it explains that instead of failing.
        useNetworkTerminalWithoutId()
        compose.awaitCondition("the setup gate follows the changed destination") { env.container.setupGate.pending }
        compose
            .onNodeWithTag("reconcileTerminal")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntilAtLeastOneExists(hasText(env.context.getString(R.string.setup_required_message)), 15_000)
        compose.onAllNodesWithTag("blockedReason").assertCountEquals(0)
    }

    @Test
    fun `test emails wait for SMTP settings`() {
        env.useSimulator()
        open("email")
        assertBlocked("sendTestEmail", R.string.email_not_configured)
    }
}
