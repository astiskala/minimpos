package app.minimpos.app.ui

import androidx.compose.ui.unit.dp
import app.minimpos.app.ui.theme.Dimens
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The size set chosen for each Adyen terminal's screen, measured between the system bars. */
class DimensTest {
    @Test
    fun `4 inch and smaller terminals get the compact sizes`() {
        // AMS1: 480×800 px at hdpi, less the status bar and Adyen's navigation bar.
        assertThat(Dimens.forWindow(320.dp, 460.dp)).isEqualTo(Dimens.Compact)
        // P630: 320×480 px at mdpi, less the status bar.
        assertThat(Dimens.forWindow(320.dp, 456.dp)).isEqualTo(Dimens.Compact)
        // Narrow screens stay compact however tall they are.
        assertThat(Dimens.forWindow(340.dp, 700.dp)).isEqualTo(Dimens.Compact)
    }

    @Test
    fun `5 to 5_5 inch terminals get the medium sizes with the compact layouts`() {
        // S1F2: 720×1280 px at xhdpi, less both bars.
        assertThat(Dimens.forWindow(360.dp, 568.dp)).isEqualTo(Dimens.Medium)
        assertThat(Dimens.Medium.compact).isTrue()
    }

    @Test
    fun `larger terminals and phones get the regular sizes`() {
        // S1E4 Pro (720×1560 px) and S1F4 Pro (720×1600 px) at xhdpi, less both bars.
        assertThat(Dimens.forWindow(360.dp, 708.dp)).isEqualTo(Dimens.Regular)
        assertThat(Dimens.forWindow(360.dp, 728.dp)).isEqualTo(Dimens.Regular)
        assertThat(Dimens.forWindow(411.dp, 819.dp)).isEqualTo(Dimens.Regular)
        assertThat(Dimens.Regular.compact).isFalse()
    }
}
