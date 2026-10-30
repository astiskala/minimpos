package app.minimpos.app.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.ui.components.PickerDialog
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The search hints of the currency and terminal pickers fit on one line, without an ellipsis, on the AMS1. */
@RunWith(RobolectricTestRunner::class)
class PickerHintsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private fun assertHintsFit() {
        var hint by mutableStateOf(R.string.settings_currency_search)
        compose.setContent {
            MiniMposTheme {
                PickerDialog(
                    title = "",
                    query = "",
                    onQueryChange = {},
                    searchHint = stringResource(hint),
                    searchTag = "search",
                    noMatches = "",
                    isEmpty = false,
                    options = { items(listOf("")) { Text(it) } },
                    onDismiss = {},
                )
            }
        }
        listOf(R.string.settings_currency_search, R.string.settings_terminal_search).forEach { resource ->
            hint = resource
            compose.waitForIdle()
            val text = env.context.getString(resource)
            val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertWithMessage("\"$text\" is cut off").that(layouts.first().isLineEllipsized(0)).isFalse()
        }
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun english() = assertHintsFit()

    @Test
    @Config(qualifiers = "es-rMX-w320dp-h460dp-hdpi")
    fun spanish() = assertHintsFit()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun french() = assertHintsFit()

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun japanese() = assertHintsFit()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h460dp-hdpi")
    fun portuguese() = assertHintsFit()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun simplifiedChinese() = assertHintsFit()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun traditionalChinese() = assertHintsFit()
}
