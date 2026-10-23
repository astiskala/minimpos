package app.minimpos.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.feature.sale.ProductBrowser
import app.minimpos.app.feature.sale.SaleUiState
import app.minimpos.app.feature.settings.BusinessReviewScreen
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.components.rememberSearchControl
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class CompactLayoutTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val business =
        ReceiptBusiness(
            "Example Cafe",
            "1/10-14 Example Street\nSurry Hills NSW\nSydney NSW 2010\nAustralia",
            "+61 2 5550 0100",
        )

    @Test
    fun `a long receipt paints its final text when scrolled through a clipped Compose layer`() {
        val receipt = ReceiptDocument(List(200) { ReceiptElement.Blank } + ReceiptElement.Text("Receipt end"))
        compose.setContent {
            MiniMposTheme {
                Column(Modifier.height(160.dp).verticalScroll(rememberScrollState()).testTag("viewport")) {
                    ReceiptPreview(receipt)
                }
            }
        }
        compose.awaitCondition("receipt layout measured") { compose.onAllNodesWithTag("receiptImage").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertThat(receiptInk()).isEqualTo(0)
        compose.onNodeWithTag("viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.awaitCondition("receipt end is drawn") { receiptInk() > 0 }
    }

    private fun receiptInk(): Int {
        val bounds = compose.onNodeWithTag("receiptImage").fetchSemanticsNode().boundsInRoot
        val view = checkNotNull(compose.onRoot().fetchSemanticsNode().root as? ViewRootForTest).view
        val bitmap = compose.runOnUiThread { createBitmap(view.width, view.height).also { view.draw(Canvas(it)) } }
        var ink = 0
        for (y in (bounds.top.toInt() + 4)..<(bounds.bottom.toInt() - 4)) {
            for (x in (bounds.left.toInt() + 4)..<(bounds.right.toInt() - 4)) {
                val pixel = bitmap[x, y]
                val dark = Color.red(pixel) < 60 && Color.green(pixel) < 60 && Color.blue(pixel) < 60
                if (Color.alpha(pixel) > 200 && dark) ink++
            }
        }
        bitmap.recycle()
        return ink
    }

    @Test
    fun `English review actions remain pinned and replacement requires Import`() = reviewFits()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Simplified Chinese review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h460dp-hdpi")
    fun `Portuguese review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "es-rMX-w320dp-h460dp-hdpi")
    fun `Spanish review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun `French review fits`() = reviewFits()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun `review actions and replacement remain reachable with large text`() = reviewFits(fontScale = 1.5f)

    private fun reviewFits(fontScale: Float = 1f) {
        var chosen: Pair<ReceiptBusiness?, Boolean>? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides env.container,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MiniMposTheme {
                    BusinessReviewScreen(business, true) { value, replace -> chosen = value to replace }
                }
            }
        }
        val import = compose.onNodeWithTag("confirmReceiptBusiness")
        val initial = import.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText(env.context.getString(R.string.action_cancel))
        cancel.assertIsDisplayed()
        assertTextFits()
        savePreview("review-$fontScale")
        compose.onNodeWithTag("supplementReceiptBusiness").performScrollTo().assertIsSelected()
        compose
            .onNodeWithTag("replaceReceiptBusiness")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertThat(chosen).isNull()
        assertThat(import.assertIsDisplayed().fetchSemanticsNode().boundsInRoot).isEqualTo(initial)
        assertTextFits()
        import.performClick()
        assertThat(chosen).isEqualTo(business to true)
        cancel.performClick()
        assertThat(chosen).isEqualTo(null to false)
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `English custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Simplified Chinese custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "es-rMX-w320dp-h460dp-hdpi")
    fun `Spanish custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun `French custom action fits`() = productsFit()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h460dp-hdpi")
    fun `Portuguese custom action is separate and product rows align`() = productsFit()

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
    fun `French products adapt to large text`() = productsFit(fontScale = 1.5f)

    @Test
    fun `compact product tiles are as tall as two name lines and the price need`() = productTilesAreTight(64.67f)

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h568dp-xhdpi")
    fun `medium product tiles are as tall as two name lines and the price need`() = productTilesAreTight(67.5f)

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h700dp-xhdpi")
    fun `regular product tiles are as tall as two name lines and the price need`() = productTilesAreTight(68f)

    /** A one-line and a two-line name give tiles of the same [height] dp: no more room than two lines and the price. */
    private fun productTilesAreTight(height: Float) {
        val products =
            listOf(
                ProductEntity(1, "Flat white", 1200, 1),
                ProductEntity(2, "Chocolate banana bread with walnuts", 1800, 1),
            )
        compose.setContent {
            MiniMposTheme {
                ProductBrowser(
                    SaleUiState(loaded = true, products = products),
                    MoneyFormatter(CurrencySpec.of("AUD"), Locale.forLanguageTag("en-AU")),
                    rememberSearchControl(false, true) {},
                    {},
                    {},
                    {},
                )
            }
        }
        listOf("product_1", "product_2").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertWithMessage(tag).that((bounds.bottom - bounds.top).value).isWithin(0.5f).of(height)
        }
    }

    private fun productsFit(fontScale: Float = 1f) {
        val products =
            listOf(
                ProductEntity(1, "Bolo de banana", 1800, 1),
                ProductEntity(2, "Café em grãos 250 g", 4500, 1),
                ProductEntity(3, "Flat white", 1200, 1),
            )
        var custom = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniMposTheme {
                    ProductBrowser(
                        SaleUiState(loaded = true, products = products),
                        MoneyFormatter(CurrencySpec.of("BRL"), Locale.forLanguageTag("pt-BR")),
                        rememberSearchControl(false, true) {},
                        {},
                        {},
                        { custom = true },
                    )
                }
            }
        }
        val action = compose.onNodeWithTag("customItem").assertIsDisplayed()
        val first =
            compose
                .onNodeWithTag("product_1")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val second =
            compose
                .onNodeWithTag("product_2")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertThat(first.top).isAtLeast(action.fetchSemanticsNode().boundsInRoot.bottom)
        if (fontScale == 1f) {
            assertThat(first.top).isEqualTo(second.top)
            assertThat(first.height).isEqualTo(second.height)
        } else {
            assertThat(second.top).isAtLeast(first.bottom)
        }
        assertTextFits()
        savePreview("sale-$fontScale")
        action.performClick()
        assertThat(custom).isTrue()
    }

    @Test
    fun `receipt previews retain complete fixed-width slips on compact screens`() {
        val receipt =
            ReceiptDocument(
                listOf(
                    ReceiptElement.Text("Example Cafe"),
                    ReceiptElement.Row("Reference", "261022-102607-2YSD"),
                    ReceiptElement.Row("Customer", "CUST-1042"),
                    ReceiptElement.Divider,
                    ReceiptElement.Row("TOTAL", "$1.00"),
                ),
            )
        compose.setContent { MiniMposTheme { ReceiptPreview(receipt) } }
        compose.awaitCondition("receipt layout measured") { compose.onAllNodesWithTag("receiptImage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("261022-102607-2YSD").assertIsDisplayed()
        savePreview("receipt")
    }

    private fun assertTextFits() {
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts)
                layouts.forEach { layout ->
                    if (layout.layoutInput.overflow != TextOverflow.Ellipsis) {
                        // TextLayoutResult also reports sub-pixel font rounding as overflow, as in ScreenTextTest.
                        val tooWide =
                            (0..<layout.lineCount).any {
                                layout.getLineRight(
                                    it,
                                ) - layout.getLineLeft(it) > layout.size.width + 1f
                            }
                        val clipped =
                            layout.multiParagraph.didExceedMaxLines || tooWide || layout.multiParagraph.height > layout.size.height + 1f
                        assertWithMessage(layout.layoutInput.text.text).that(clipped).isFalse()
                    }
                }
            }
    }

    private fun savePreview(name: String) {
        val locale =
            env.context.resources.configuration.locales[0]
                .toLanguageTag()
        val file = File("build/reports/compact-layouts/$locale-$name.png").apply { parentFile?.mkdirs() }
        val view = checkNotNull(compose.onRoot().fetchSemanticsNode().root as? ViewRootForTest).view
        val bitmap = compose.runOnUiThread { createBitmap(view.width, view.height).also { view.draw(Canvas(it)) } }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
