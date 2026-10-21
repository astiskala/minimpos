package app.minimpos.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * The main screens at AMS1 size (about 320×460 dp): in every supported language no text is cut off without an ellipsis,
 * and in the en-XA pseudo-locale (accented and longer, made for debug builds) no translatable text is still shown in
 * English, which would mean it is hard-coded or read in the wrong language.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenTextTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "AUD"), receipt = it.receipt.copy(autoPrint = false, businessName = "Cafe"))
        }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    /** Visits the main screens, calling [check] with each screen's name once it shows and its transition has ended. */
    private fun visitMainScreens(check: (String) -> Unit) {
        val settled: (String) -> Unit = { screen ->
            compose.waitForIdle()
            check(screen)
        }
        compose.setContent { MiniMposApp(container) }
        waitForTag("newSale")
        settled("Home")
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        settled("custom item keypad")
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("charge") and isEnabled(), 15_000)
        settled("cart")
        compose.clickWhenEnabled("charge")
        waitForTag("pay")
        settled("checkout")
        compose.onNodeWithTag("pay").performClick()
        waitForTag("resultStatus")
        compose.waitForIdle()
        settled("payment result")
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForTag("historyList")
        settled("history")
        compose.onNodeWithTag("back").performClick()
        waitForTag("products")
        compose.onNodeWithTag("products").performClick()
        waitForTag("addFab")
        settled("products")
        compose.onNodeWithTag("back").performClick()
        waitForTag("settings")
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        settled("settings")
    }

    /** Every text and content description on screen, with its layout when it is text. */
    private fun shownTexts(): List<Pair<String, TextLayoutResult?>> =
        compose
            .onAllNodes(
                SemanticsMatcher("has text") {
                    SemanticsProperties.Text in it.config ||
                        SemanticsProperties.ContentDescription in it.config
                },
                true,
            ).fetchSemanticsNodes()
            .flatMap { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts)
                val texts =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                texts.mapIndexed { index, text -> text to layouts.getOrNull(index) } +
                    node.config
                        .getOrNull(SemanticsProperties.ContentDescription)
                        .orEmpty()
                        .map { it to null }
            }

    /**
     * Whether [layout] lost lines or characters without an ellipsis: more lines than allowed, a line wider than its box
     * or lines taller than it, by more than a pixel ([TextLayoutResult.hasVisualOverflow] also counts sub-pixel rounding).
     */
    private fun cutOff(layout: TextLayoutResult): Boolean {
        val tooWide = (0..<layout.lineCount).any { layout.getLineRight(it) - layout.getLineLeft(it) - layout.size.width > 1f }
        val lost = layout.multiParagraph.didExceedMaxLines || tooWide || layout.multiParagraph.height - layout.size.height > 1f
        return lost && layout.layoutInput.overflow != TextOverflow.Ellipsis
    }

    private fun assertNothingCutOff() {
        val cutOff = mutableListOf<String>()
        visitMainScreens { screen ->
            shownTexts().forEach { (text, layout) ->
                if (layout != null && cutOff(layout)) {
                    cutOff += "$screen: \"$text\" (${layout.multiParagraph.width}×${layout.multiParagraph.height} in ${layout.size})"
                }
            }
        }
        assertWithMessage("Text cut off without an ellipsis at AMS1 size").that(cutOff).isEmpty()
    }

    private fun layout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `the check finds clipped text and accepts text that fits or ends in an ellipsis`() {
        val long = "A description far too long for its box"
        compose.setContent {
            Column {
                Text(long, Modifier.width(40.dp).testTag("clipped"), maxLines = 1, overflow = TextOverflow.Clip)
                Text(long, Modifier.width(40.dp).testTag("cutLines"), maxLines = 2)
                Text(long, Modifier.width(40.dp).testTag("ellipsis"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Fits", Modifier.testTag("fits"))
            }
        }
        assertThat(cutOff(layout("clipped"))).isTrue()
        assertThat(cutOff(layout("cutLines"))).isTrue()
        assertThat(cutOff(layout("ellipsis"))).isFalse()
        assertThat(cutOff(layout("fits"))).isFalse()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `no English text is cut off at AMS1 size`() = assertNothingCutOff()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `no Simplified Chinese text is cut off at AMS1 size`() = assertNothingCutOff()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `no Traditional Chinese text is cut off at AMS1 size`() = assertNothingCutOff()

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `no Japanese text is cut off at AMS1 size`() = assertNothingCutOff()

    @Test
    @Config(qualifiers = "en-rXA-w320dp-h460dp-hdpi")
    fun `every translatable text is shown translated in the pseudo-locale`() {
        fun strings(tag: String): List<String> {
            val configuration = Configuration(env.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val resources = env.context.createConfigurationContext(configuration).resources
            return R.string::class.java.fields.map { resources.getString(it.getInt(null)) }
        }
        val english = strings("en")
        val pseudo = strings("en-XA")
        // Pseudo-localisation changes every translatable string; the ones it leaves alone are not translatable.
        assertThat(english.zip(pseudo).count { (plain, accented) -> plain != accented }).isGreaterThan(english.size / 2)
        val translatable =
            english
                .zip(pseudo)
                .filter { (plain, accented) -> plain != accented && plain.length >= 3 && '%' !in plain && plain.any(Char::isLetter) }
                .map { it.first }
                .toSet()
        // A hard-coded English label such as Cancel would be reported.
        assertThat(translatable).contains(english[R.string::class.java.fields.indexOfFirst { it.name == "action_cancel" }])
        val untranslated = mutableListOf<String>()
        visitMainScreens { screen ->
            shownTexts().map { it.first }.filter { it in translatable }.forEach { untranslated += "$screen: \"$it\"" }
        }
        assertWithMessage("Text shown in English instead of the app language").that(untranslated.distinct()).isEmpty()
    }
}
