package app.minimpos.app.ui

import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.core.giftcard.GiftCardProvider
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

    /** Every smaller tile's label by its tag, in the order Home shows them. */
    private val labels =
        mapOf(
            "newSale" to R.string.home_new_sale,
            "preAuth" to R.string.home_pre_auth,
            "savedCard" to R.string.home_saved_card,
            "giftCards" to R.string.home_gift_cards,
            "cashout" to R.string.cashout_title,
            "refund" to R.string.home_refund,
            "history" to R.string.home_history,
            "reconciliation" to R.string.home_reconciliation,
        )

    /** The smaller tiles of a terminal that is set up and uses every feature. */
    private val everyTile = labels.keys - "newSale"

    /** The smaller tiles of a terminal that is set up, as installed. */
    private val installedTiles = everyTile - setOf("giftCards", "cashout")

    @Before
    fun setUp() {
        env.updateSettings { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    /** The shared key and the Checkout API are entered, so Home offers a sale rather than the setup. */
    private fun configureKey() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "mini-key")) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
        env.useCheckoutApi()
    }

    /**
     * Set up (or with [simulator], the demo under its banner), with a gift-card provider added and cashout switched on:
     * the most tiles Home ever shows.
     */
    private fun showEveryTile(simulator: Boolean = false) {
        if (simulator) env.useSimulator() else configureKey()
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        giftCards = listOf(GiftCardSetting(GiftCardProvider.SVS)),
                        cashback = it.payment.cashback.copy(standalone = true),
                    ),
            )
        }
        compose.setContent { MiniMposApp(container) }
        waitForTag("cashout")
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese home fits AMS1`() = homeTextsShareSizes()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese home fits AMS1`() = homeTextsShareSizes()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese home fits AMS1`() = homeTextsShareSizes()

    @Test
    fun `home puts the sale first, then what starts a payment, then what follows one, two per row`() {
        showEveryTile()
        val sale = bounds("newSale")
        var previous = sale.bottom
        listOf("preAuth" to "savedCard", "giftCards" to "cashout", "refund" to "history").forEach { (left, right) ->
            val a = bounds(left)
            val b = bounds(right)
            assertWithMessage("$left and $right").that(a.top).isEqualTo(b.top)
            assertThat(a.left).isLessThan(b.left)
            assertThat(a.width.value).isWithin(1f).of(b.width.value)
            assertThat(a.top).isAtLeast(previous)
            previous = a.bottom
        }
        // The odd last tile of a zone spans its row, as wide as the sale tile.
        val last = bounds("reconciliation")
        assertThat(last.top).isAtLeast(previous)
        assertThat(last.width).isEqualTo(sale.width)
        // The sale tile is the only large one.
        everyTile.forEach { assertWithMessage(it).that(bounds(it).height).isLessThan(sale.height) }
    }

    @Test
    fun `every tile fits the smallest terminal without scrolling`() = everyTileIsInView()

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `every tile fits the P630 without scrolling`() = everyTileIsInView()

    @Test
    fun `every tile fits the smallest terminal under the simulator banner`() = everyTileIsInView(simulator = true)

    private fun everyTileIsInView(simulator: Boolean = false) {
        showEveryTile(simulator)
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        // The screen's margin (12 dp on these screens) stays under the last tile, so nothing scrolls.
        (everyTile + "newSale").forEach { assertWithMessage(it).that(bounds(it).bottom).isAtMost(screen.bottom - 12.dp) }
        assertHomeTilesUsable(everyTile)
    }

    @Test
    fun `home leaves out what is switched off and the tiles left share the rows`() {
        configureKey()
        compose.setContent { MiniMposApp(container) }
        waitForTag("preAuth")
        listOf("giftCards", "cashout").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        assertThat(bounds("preAuth").top).isEqualTo(bounds("savedCard").top)
        assertThat(bounds("refund").top).isEqualTo(bounds("history").top)
        assertThat(bounds("reconciliation").width).isEqualTo(bounds("newSale").width)
        assertHomeTilesUsable(installedTiles)

        // Without card saving there are no tokens to charge, so Pre-authorization takes the row.
        env.updateSettings { it.copy(payment = it.payment.copy(offerCardSaving = false)) }
        compose.waitUntilDoesNotExist(hasTestTag("savedCard"), 15_000)
        assertThat(bounds("preAuth").width).isEqualTo(bounds("newSale").width)
        assertHomeTilesUsable(installedTiles - "savedCard")
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `tiles stop growing on a tall screen and stay at the top`() {
        configureKey()
        compose.setContent { MiniMposApp(container) }
        waitForTag("preAuth")
        val sale = bounds("newSale")
        assertThat(sale.height).isEqualTo(144.dp)
        installedTiles.forEach { assertWithMessage(it).that(bounds(it).height).isEqualTo(100.dp) }
        // Nothing but the app bar and the screen's margin is above the sale tile, and the rest stays empty.
        assertThat(sale.top).isLessThan(100.dp)
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        assertThat(screen.bottom - bounds("reconciliation").bottom).isGreaterThan(200.dp)
    }

    @Test
    fun `before setup the large tile finishes it and new sale joins the smaller tiles`() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("terminalSetup")
        compose
            .onNodeWithTag("terminalSetup")
            .assertTextContains(env.context.getString(R.string.home_setup))
            .assertTextContains(env.context.getString(R.string.home_setup_text))
        val setup = bounds("terminalSetup")
        val sale = bounds("newSale")
        assertThat(sale.top).isAtLeast(setup.bottom)
        assertThat(sale.top).isEqualTo(bounds("preAuth").top)
        assertThat(sale.height).isLessThan(setup.height)
        assertHomeTilesUsable(installedTiles + "newSale", large = "terminalSetup")
    }

    @Test
    fun `history counts what needs attention and opens on it`() {
        configureKey()
        await {
            val lost =
                SaleEntity(
                    id = "lost",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1000,
                    taxMinor = 0,
                    totalMinor = 1000,
                    status = SaleStatus.UNKNOWN,
                    merchantReference = "LOST",
                )
            container.sales.createPending(lost, emptyList())
            container.sales.createPending(
                lost.copy(id = "paid", status = SaleStatus.APPROVED, paymentBrand = "visa", merchantReference = "PAID"),
                emptyList(),
            )
        }
        compose.setContent { MiniMposApp(container) }
        compose.waitUntil(
            15_000,
        ) { compose.onAllNodesWithTag("historyAttention", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The count sits in the tile, which is no taller for it and reads it out with its label.
        assertThat(bounds("history").height).isEqualTo(bounds("refund").height)
        compose
            .onNodeWithTag("history")
            .assertTextContains(env.context.getString(R.string.home_history))
            .assertTextContains(env.context.resources.getQuantityString(R.plurals.home_history_attention, 1, 1))
            .performClick()
        val issues = env.context.getString(R.string.history_filter_issues)
        waitForTag("paymentMethod")
        // The last chip stays fully visible once the payment method menu is present.
        compose.onNodeWithText(issues).assertIsSelected().assertIsDisplayed()
        assertThat(
            compose.onNodeWithText(issues).getUnclippedBoundsInRoot().right,
        ).isAtMost(compose.onRoot().getUnclippedBoundsInRoot().right)
        compose.waitUntilAtLeastOneExists(hasText("LOST", substring = true), 15_000)
        compose.onNodeWithText("PAID", substring = true).assertDoesNotExist()
    }

    @Test
    fun `history without anything waiting shows no count and opens on everything`() {
        configureKey()
        compose.setContent { MiniMposApp(container) }
        waitForTag("history")
        compose.onNodeWithTag("historyAttention", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history").performClick()
        val all = env.context.getString(R.string.history_filter_all)
        compose.waitUntilAtLeastOneExists(hasText(all), 15_000)
        compose.onNodeWithText(all).assertIsSelected()
    }

    @Test
    fun `the sale tile's hint is not cut on the smallest terminal`() = saleHintFits()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun `the French sale tile's hint is not cut on the smallest terminal`() = saleHintFits()

    /** The sale tile's hint is drawn whole, wrapping rather than ending in an ellipsis, even between the most tiles. */
    private fun saleHintFits() {
        showEveryTile()
        val text = env.context.getString(R.string.home_new_sale_hint)
        val hint = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val tile = bounds("newSale")
        assertThat(hint.top).isAtLeast(tile.top)
        assertThat(hint.bottom).isAtMost(tile.bottom)
        val layout = textLayout(text)
        assertWithMessage(text).that(layout.isLineEllipsized(layout.lineCount - 1)).isFalse()
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
    @Config(qualifiers = "en-rAU-w800dp-h360dp-xhdpi")
    fun `home scrolls rather than squeezing its tiles on a landscape phone`() {
        showEveryTile()
        assertHomeTilesUsable(everyTile)
    }

    @Test
    fun `home scrolls rather than cutting labels with the largest text`() {
        RuntimeEnvironment.setFontScale(2f)
        showEveryTile()
        assertHomeTilesUsable(everyTile)
    }

    @Test
    fun `home tiles remain usable at one and a half font scale`() {
        RuntimeEnvironment.setFontScale(1.5f)
        showEveryTile()
        assertHomeTilesUsable(everyTile)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h460dp-hdpi")
    fun `RTL home tiles remain usable at one and a half font scale`() {
        RuntimeEnvironment.setFontScale(1.5f)
        showEveryTile()
        assertThat(env.context.resources.configuration.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
        assertHomeTilesUsable(everyTile)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h460dp-hdpi")
    fun `RTL home tiles remain usable at double font scale`() {
        RuntimeEnvironment.setFontScale(2f)
        showEveryTile()
        assertThat(env.context.resources.configuration.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
        assertHomeTilesUsable(everyTile)
    }

    /**
     * The smaller tiles show their labels at one size, whatever each label needs alone (Refund needs the least and
     * Reconciliation, alone in its row, has the most room), with the icon above the label rather than beside it. The
     * sale tile's title stays on one line.
     */
    private fun homeTextsShareSizes() {
        showEveryTile()
        val sizes = everyTile.map { drawnSize(env.context.getString(labels.getValue(it)), it) }
        assertWithMessage("tile label sizes $sizes").that(sizes.toSet()).hasSize(1)
        assertThat(sizes.first()).isAtLeast(12f)
        assertThat(drawnSize(env.context.getString(R.string.home_new_sale), "newSale")).isAtLeast(16f)
        everyTile.forEach { tag ->
            val tile = compose.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            val text =
                compose
                    .onNodeWithText(
                        env.context.getString(labels.getValue(tag)),
                        useUnmergedTree = true,
                    ).getUnclippedBoundsInRoot()
            // The icon (at least 24 dp) and the tile's padding are above the label, so it is never beside the icon.
            assertWithMessage("$tag icon above label").that(text.top - tile.top).isAtLeast(24.dp + 8.dp)
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        return layouts.single()
    }

    /** The font size, in sp, the tile tagged [tileTag] draws [text] at, whole on one line. */
    private fun drawnSize(
        text: String,
        tileTag: String,
    ): Float {
        compose.onNodeWithTag(tileTag).performScrollTo()
        val layout = textLayout(text)
        assertWithMessage("$text lines").that(layout.lineCount).isEqualTo(1)
        assertWithMessage("$text cut").that(layout.isLineEllipsized(0)).isFalse()
        return layout.layoutInput.style.fontSize.value
    }

    /**
     * The [large] tile and each of the smaller [tiles] are at least 48 dp tall once scrolled to, with every smaller
     * tile's label inside it rather than cut off; Settings stays in the app bar.
     */
    private fun assertHomeTilesUsable(
        tiles: Set<String>,
        large: String = "newSale",
    ) {
        waitForTag(large)
        assertWithMessage(large)
            .that(
                compose
                    .onNodeWithTag(large)
                    .performScrollTo()
                    .getUnclippedBoundsInRoot()
                    .height,
            ).isAtLeast(48.dp)
        tiles.forEach { tag ->
            val tile = compose.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            val text =
                compose
                    .onNodeWithText(
                        env.context.getString(labels.getValue(tag)),
                        useUnmergedTree = true,
                    ).getUnclippedBoundsInRoot()
            assertWithMessage(tag).that(tile.height).isAtLeast(48.dp)
            assertWithMessage("$tag label top").that(text.top).isAtLeast(tile.top)
            assertWithMessage("$tag label bottom").that(text.bottom).isAtMost(tile.bottom)
            // A label squeezed by its tile measures far less than a line.
            assertWithMessage("$tag label height").that(text.height).isAtLeast(14.dp)
        }
        val button = compose.onNodeWithTag("settings").assertIsDisplayed().fetchSemanticsNode()
        // Compact buttons draw at 44 dp; Compose expands their effective touch area independently.
        val minimum = with(button.layoutInfo.density) { 48.dp.toPx() }
        assertThat(button.touchBoundsInRoot.height).isAtLeast(minimum)
        assertThat(button.touchBoundsInRoot.width).isAtLeast(minimum)
    }
}
