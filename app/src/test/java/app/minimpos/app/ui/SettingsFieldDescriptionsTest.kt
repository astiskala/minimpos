package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.settings.SecretField
import app.minimpos.app.feature.settings.SettingTextField
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SettingsFieldDescriptionsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `title and primary description precede editable input while additional information follows it`() {
        var saved = ""
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    Column {
                        SettingTextField(
                            label = "Reference prefix",
                            value = "",
                            onCommit = { saved = it },
                            description = "Explain the field before entry.",
                            supporting = "Additional information.",
                            tag = "entry",
                        )
                    }
                }
            }
        }
        val title = compose.onNodeWithText("Reference prefix").getUnclippedBoundsInRoot()
        val description = compose.onNodeWithText("Explain the field before entry.").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("entry").getUnclippedBoundsInRoot()
        val note = compose.onNodeWithText("Additional information.").getUnclippedBoundsInRoot()
        assertThat(title.bottom.value).isAtMost(description.top.value)
        assertThat(description.bottom.value).isAtMost(input.top.value)
        assertThat(input.bottom.value).isAtMost(note.top.value)
        compose.onNodeWithTag("entry").assertContentDescriptionEquals("Reference prefix").performTextReplacement("SHOP")
        assertThat(saved).isEqualTo("SHOP")
    }

    @Test
    fun `secret keeps an accessible editable field below its external title`() {
        var saved = ""
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    SecretField("API key", false, "", { saved = it }, {}, tag = "secret")
                }
            }
        }
        val title = compose.onNodeWithText("API key").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithTag("secret").getUnclippedBoundsInRoot()
        assertThat(title.bottom.value).isAtMost(input.top.value)
        compose.onNodeWithTag("secret").assertContentDescriptionEquals("API key").performTextReplacement("synthetic")
        assertThat(saved).isEqualTo("synthetic")
    }
}
