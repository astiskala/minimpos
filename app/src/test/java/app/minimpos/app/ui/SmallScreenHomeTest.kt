package app.minimpos.app.ui

import android.view.View
import androidx.compose.material3.Typography
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** The Home tiles on the smallest terminals, on other screens and at large text sizes; see [SmallScreenTest]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SmallScreenHomeTest {
    private val terminal = FakeTerminal(passphrase = "correct horse battery staple")

    @get:Rule(order = 0)
    val env = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), terminal)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.updateSettings { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `home tiles keep their labels and touch size beside the setup card on the smallest terminal`() {
        compose.setContent { MiniMposApp(container) }
        assertHomeTilesUsable()
        // Settings stays in the app bar; tile content can scroll when setup needs more room.
    }

    @Test
    fun `home tile labels share one font size on the smallest terminal`() = homeTextsShareSizes()

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h568dp-xhdpi")
    fun `home tile labels share one font size on the S1F2`() = homeTextsShareSizes()

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h640dp-xhdpi")
    fun `home tile labels share one font size on a full-height S1F2 screen`() = homeTextsShareSizes()

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `home tiles keep their labels on the P630`() {
        compose.setContent { MiniMposApp(container) }
        assertHomeTilesUsable()
    }

    @Test
    @Config(qualifiers = "en-rAU-w800dp-h360dp-xhdpi")
    fun `home scrolls rather than squeezing its tiles on a landscape phone`() {
        compose.setContent { MiniMposApp(container) }
        assertHomeTilesUsable()
    }

    @Test
    fun `home scrolls rather than cutting labels with the largest text`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { MiniMposApp(container) }
        assertHomeTilesUsable()
    }

    @Test
    fun `home tiles remain usable at one and a half font scale`() {
        RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { MiniMposApp(container) }
        assertHomeTilesUsable()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h460dp-hdpi")
    fun `RTL home tiles remain usable at one and a half font scale`() {
        RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { MiniMposApp(container) }
        assertThat(env.context.resources.configuration.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
        assertHomeTilesUsable()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h460dp-hdpi")
    fun `RTL home tiles remain usable at double font scale`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { MiniMposApp(container) }
        assertThat(env.context.resources.configuration.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
        assertHomeTilesUsable()
    }

    /**
     * The four half-width tiles show their labels at one size, whatever each label needs alone (Reconciliation needs
     * the least), with the icon above the label rather than beside it; the two payment tiles do the same with their
     * titles, which stay on one line.
     */
    private fun homeTextsShareSizes() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("newSale")
        val tileLabel = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold)
        val tiles =
            mapOf(
                "savedCard" to R.string.home_saved_card,
                "refund" to R.string.home_refund,
                "history" to R.string.home_history,
                "reconciliation" to R.string.home_reconciliation,
            )
        val sizes = tiles.map { (tag, label) -> drawnSize(env.context.getString(label), tileLabel, tag) }
        assertWithMessage("tile label sizes $sizes").that(sizes.max() - sizes.min()).isLessThan(0.5f)
        val title = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold)
        val titles =
            mapOf("newSale" to R.string.home_new_sale, "preAuth" to R.string.home_pre_auth)
                .map { (tag, text) -> drawnSize(env.context.getString(text), title, tag) }
        assertWithMessage("payment title sizes $titles").that(titles.max() - titles.min()).isLessThan(0.5f)
        tiles.forEach { (tag, label) ->
            val tile = compose.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            val text = compose.onNodeWithText(env.context.getString(label), useUnmergedTree = true).getUnclippedBoundsInRoot()
            // The icon (at least 24 dp) and the tile's padding are above the label, so it is never beside the icon.
            assertWithMessage("$tag icon above label").that(text.top - tile.top).isAtLeast(24.dp + 8.dp)
        }
    }

    /** The font size [text] is drawn at: [style]'s size scaled by its drawn width over its width at that size. */
    private fun drawnSize(
        text: String,
        style: TextStyle,
        tileTag: String,
    ): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tileTag).performScrollTo()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        assertWithMessage("$text lines").that(layouts.single().lineCount).isEqualTo(1)
        val drawn = layouts.single().let { it.getLineRight(0) - it.getLineLeft(0) }
        val measurer = TextMeasurer(createFontFamilyResolver(env.context), Density(env.context), LayoutDirection.Ltr)
        return style.fontSize.value * drawn / measurer.measure(text, style, softWrap = false).size.width
    }

    /** Every Home tile is at least 48 dp tall once scrolled to, with its label inside it rather than cut off. */
    private fun assertHomeTilesUsable() {
        waitForTag("newSale")
        listOf("newSale", "preAuth").forEach { tag ->
            val tile = compose.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            assertWithMessage(tag).that(tile.height).isAtLeast(48.dp)
        }
        mapOf(
            "savedCard" to R.string.home_saved_card,
            "refund" to R.string.home_refund,
            "history" to R.string.home_history,
            "reconciliation" to R.string.home_reconciliation,
        ).forEach { (tag, label) ->
            val tile = compose.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            val text = compose.onNodeWithText(env.context.getString(label), useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertWithMessage(tag).that(tile.height).isAtLeast(48.dp)
            assertWithMessage("$tag label top").that(text.top).isAtLeast(tile.top)
            assertWithMessage("$tag label bottom").that(text.bottom).isAtMost(tile.bottom)
            // A label squeezed by its tile measures far less than a line.
            assertWithMessage("$tag label height").that(text.height).isAtLeast(14.dp)
        }
        listOf("settings").forEach {
            val button =
                compose
                    .onNodeWithTag(it)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            // Compact buttons draw at 44 dp; Compose expands their effective touch area independently.
            val minimum = with(button.layoutInfo.density) { 48.dp.toPx() }
            assertWithMessage(it).that(button.touchBoundsInRoot.height).isAtLeast(minimum)
            assertWithMessage(it).that(button.touchBoundsInRoot.width).isAtLeast(minimum)
        }
    }
}
