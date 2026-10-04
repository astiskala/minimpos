package io.github.astiskala.minimpos.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.ui.components.SwitchRow
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SwitchRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `label and switch are one accessible toggle with a single state change per tap`() {
        compose.setContent {
            MiniMposTheme {
                var checked by remember { mutableStateOf(false) }
                SwitchRow("Option", checked, { checked = it }, subtitle = "Explanation")
            }
        }
        compose.onAllNodes(isToggleable(), useUnmergedTree = true).assertCountEquals(1)
        compose
            .onNodeWithText(
                "Option",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
        compose.onNodeWithText("Explanation").assertIsOn().performClick()
        compose.onNodeWithText("Option").assertIsOff()
    }

    @Test
    fun `unavailable options are disabled and never invoke their action`() {
        compose.setContent {
            MiniMposTheme {
                SwitchRow("Unavailable", false, { error("Disabled toggle changed") }, enabled = false)
            }
        }
        compose
            .onNodeWithText("Unavailable")
            .assertHeightIsAtLeast(48.dp)
            .assertIsNotEnabled()
            .assertIsOff()
            .performClick()
        compose.onNodeWithText("Unavailable").assertIsOff()
    }
}
