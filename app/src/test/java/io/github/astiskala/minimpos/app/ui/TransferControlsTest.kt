package io.github.astiskala.minimpos.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.feature.transfer.CodeControls
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TransferControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun playbackLabels(
        pause: String,
        play: String,
    ) {
        compose.setContent {
            MiniMposTheme {
                var playing by remember { mutableStateOf(true) }
                Box(Modifier.padding(LocalDimens.current.screenPadding)) {
                    CodeControls(1, 3, playing, onStep = {}, onTogglePlay = { playing = !playing })
                }
            }
        }
        compose.onNodeWithText(pause).assertIsDisplayed().performClick()
        compose.onNodeWithText(play).assertIsDisplayed().performClick()
        compose.onNodeWithText(pause).assertIsDisplayed()
    }

    @Test
    fun `playback has visible Play and Pause labels on the smallest screen`() = playbackLabels("Pause", "Play")

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese playback labels fit the smallest screen`() = playbackLabels("暂停", "播放")

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese playback labels fit the smallest screen`() = playbackLabels("一時停止", "再生")

    @Test
    fun `next and previous step codes independently of playback`() {
        val steps = mutableListOf<Int>()
        var toggles = 0
        compose.setContent {
            MiniMposTheme {
                Box(Modifier.padding(LocalDimens.current.screenPadding)) {
                    CodeControls(1, 3, false, onStep = { steps += it }, onTogglePlay = { toggles++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Previous").performClick()
        assertThat(steps).containsExactly(1, -1).inOrder()
        assertThat(toggles).isEqualTo(0)
        compose.onNodeWithText("Play").performClick()
        assertThat(toggles).isEqualTo(1)
        assertThat(steps).hasSize(2)
    }
}
