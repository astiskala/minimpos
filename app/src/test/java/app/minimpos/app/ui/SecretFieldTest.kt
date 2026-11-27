package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.settings.SecretField
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The secret field over its caller's String: hidden until shown, submitted whole, and emptied when the caller saves. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SecretFieldTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private var value by mutableStateOf("")
    private var saved by mutableStateOf(false)
    private val submitted = mutableListOf<String>()

    private fun showField() =
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    SecretField(
                        "API key",
                        isSet = saved,
                        value = value,
                        onValueChange = { value = it },
                        onSubmit = { submitted += value },
                        tag = "secret",
                        hint = "1234",
                    )
                }
            }
        }

    private fun obscured(hidden: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.IsPasswordObfuscated, hidden)

    @Test
    fun `a typed secret stays hidden until shown and reaches its caller`() {
        showField()
        compose.onNodeWithTag("secret").performTextInput("AQE-secret")
        compose.waitUntil(5_000) { value == "AQE-secret" }
        compose.onNodeWithTag("secret").assert(obscured(true))
        compose.onNodeWithContentDescription("Show").performClick()
        compose.onNodeWithTag("secret").assert(obscured(false))
        compose.onNodeWithContentDescription("Hide").performClick()
        compose.onNodeWithTag("secret").assert(obscured(true))
    }

    @Test
    fun `Done submits everything typed even before typing reaches the caller`() {
        showField()
        compose.onNodeWithTag("secret").performClick()
        val field = compose.onNodeWithTag("secret").fetchSemanticsNode()
        // In one main-thread task, as when the keyboard sends the last character and Done together.
        compose.runOnUiThread {
            checkNotNull(field.config[SemanticsActions.InsertTextAtCursor].action)(AnnotatedString("AQE-secret"))
            checkNotNull(field.config[SemanticsActions.OnImeAction].action)()
        }
        assertThat(submitted).containsExactly("AQE-secret")
    }

    @Test
    fun `a saved secret shows masked until focused and saving a replacement empties the field`() {
        saved = true
        showField()
        compose.onNodeWithText("••••••••1234").assertExists()
        compose.onNodeWithContentDescription("Saved").assertExists()
        compose.onNodeWithTag("secret").performClick()
        compose.onNodeWithText("••••••••1234").assertDoesNotExist()
        compose.onNodeWithText("Type to replace").assertExists()
        compose.onNodeWithTag("secret").performTextInput("AQE-new")
        compose.waitUntil(5_000) { value == "AQE-new" }
        // What the caller does once the replacement is stored.
        value = ""
        val emptied = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        compose.waitUntil(5_000) { emptied.matches(compose.onNodeWithTag("secret").fetchSemanticsNode()) }
        compose.onNodeWithTag("secret").performTextInput("next")
        compose.waitUntil(5_000) { value == "next" }
    }
}
