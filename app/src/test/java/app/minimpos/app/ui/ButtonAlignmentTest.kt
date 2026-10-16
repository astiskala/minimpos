package app.minimpos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ButtonAlignmentTest {
    @get:Rule
    val compose = createRecordingComposeRule()

    @Test
    fun `pay remaining text stays centered when wrapped beside Home`() {
        compose.setContent {
            MiniMposTheme { Box(Modifier.width(160.dp)) { PrimaryButton("Pay remaining $10.69", {}) } }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Pay remaining $10.69").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts.single().lineCount).isGreaterThan(1)
        assertThat(
            layouts
                .single()
                .layoutInput.style.textAlign,
        ).isEqualTo(TextAlign.Center)
    }
}
