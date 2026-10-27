package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.settings.LogoEditorState
import app.minimpos.app.feature.settings.ReceiptLogoEditor
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.logo.ArgbImage
import app.minimpos.core.logo.LogoConverter
import app.minimpos.core.logo.LogoOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ReceiptLogoLayoutTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `editor controls scroll with Save visible on AMS1`() {
        env.useSimulator()
        val result = LogoConverter.convert(ArgbImage(2, 2, intArrayOf(-1, -16_777_216, -16_777_216, -1)))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    ReceiptLogoEditor(LogoEditorState(result = result), {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("saveReceiptLogo").assertIsDisplayed()
        compose.onNodeWithTag("receiptLogoSize").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("saveReceiptLogo").assertIsDisplayed()
    }

    @Test
    fun `darkness converts once, when the drag ends`() {
        env.useSimulator()
        val result = LogoConverter.convert(ArgbImage(2, 2, intArrayOf(-1, -16_777_216, -16_777_216, -1)))
        val updates = mutableListOf<LogoOptions>()
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    ReceiptLogoEditor(LogoEditorState(LogoOptions(threshold = 127), result), { updates += it }, {}, {})
                }
            }
        }
        val slider = compose.onNodeWithTag("receiptLogoDarkness").performScrollTo()
        slider.performTouchInput {
            down(center)
            moveBy(Offset(viewConfiguration.touchSlop * 2, 0f))
            moveTo(centerRight)
        }
        compose.waitForIdle()
        assertThat(updates).isEmpty()
        slider.performTouchInput { up() }
        compose.waitForIdle()
        assertThat(updates).hasSize(1)
        assertThat(checkNotNull(updates.single().threshold)).isGreaterThan(200)
    }
}
