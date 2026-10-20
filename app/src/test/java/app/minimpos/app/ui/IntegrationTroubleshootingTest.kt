package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.settings.IntegrationTroubleshooting
import app.minimpos.app.feature.settings.TerminalSetupActions
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.app.terminal.SetupDiagnostics
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class IntegrationTroubleshootingTest {
    @get:Rule
    val compose = createRecordingComposeRule()

    private val reading =
        SetupDiagnostics(
            ApiCheck.Works,
            TerminalConnection.Unknown,
            0,
            mapOf("apiHasRequiredRoles" to "true", "apiRolesMissing" to "[]"),
        )

    @Test
    fun `each expansion hides stale content and sharing until centered loading finishes`() {
        var actions by mutableStateOf(TerminalSetupActions(integrationDiagnostics = reading))
        var refreshes = 0
        compose.setContent {
            MiniMposTheme {
                Column(Modifier.fillMaxWidth().testTag("detailsContainer").verticalScroll(rememberScrollState())) {
                    IntegrationTroubleshooting(
                        TerminalState(),
                        actions,
                        onRefresh = {
                            refreshes++
                            actions = actions.copy(checkingDiagnostics = true)
                        },
                    )
                }
            }
        }
        repeat(2) {
            compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
            compose.onNodeWithTag("diagnosticsLoading").assertIsDisplayed()
            compose.onNodeWithTag("diagnostic_appVersion").assertDoesNotExist()
            compose.onNodeWithTag("shareTapReport").assertDoesNotExist()
            val spinner = compose.onNodeWithTag("diagnosticsLoading").fetchSemanticsNode().boundsInRoot
            val container = compose.onNodeWithTag("detailsContainer").fetchSemanticsNode().boundsInRoot
            assertThat(spinner.center.x).isWithin(1f).of(container.center.x)
            compose.runOnIdle { actions = actions.copy(checkingDiagnostics = false, integrationDiagnostics = reading) }
            compose.onNodeWithTag("diagnosticsLoading").assertDoesNotExist()
            compose.onNodeWithTag("diagnostic_apiHasRequiredRoles").performScrollTo().assertTextContains("true")
            compose.onNodeWithTag("diagnostic_apiRolesMissing").performScrollTo().assertTextContains("[]")
            compose.onNodeWithTag("shareTapReport").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
        }
        compose.runOnIdle { assertThat(refreshes).isEqualTo(2) }
    }
}
