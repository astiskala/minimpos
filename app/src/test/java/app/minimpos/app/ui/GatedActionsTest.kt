package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actions whose precondition is known before they are pressed are disabled, with the fix shown under them. */
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
        compose.onNodeWithTag("settings").performClick()
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
    fun `scanned wallet refresh waits for the terminal ID`() {
        useNetworkTerminalWithoutId()
        open("terminal")
        assertBlocked("refreshWallets", R.string.setup_poiid)
    }

    @Test
    fun `capability refresh waits for the terminal ID`() {
        useNetworkTerminalWithoutId()
        open("capabilities")
        assertBlocked("refreshCapabilities", R.string.setup_poiid)
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
        useNetworkTerminalWithoutId()
        compose.waitUntilAtLeastOneExists(hasTestTag("blockedReason"), 15_000)
        compose.onNodeWithTag("reconcileTerminal").assertIsNotEnabled()
        compose.onAllNodesWithTag("blockedReason")[0].assertTextContains(env.context.getString(R.string.setup_poiid))
    }

    @Test
    fun `test emails wait for SMTP settings`() {
        env.useSimulator()
        open("email")
        assertBlocked("sendTestEmail", R.string.email_not_configured)
    }
}
