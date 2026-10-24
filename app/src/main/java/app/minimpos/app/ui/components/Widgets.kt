package app.minimpos.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.AppContainer
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.qr.QrCodes
import app.minimpos.app.qr.QrShapes
import app.minimpos.app.share.ReceiptImage
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.Align
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.TextStyle
import app.minimpos.terminal.client.PrintAlign
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import android.graphics.Color as AndroidColor

/** The process's [AppContainer], provided by `MiniMposApp`; screens use it to build their view models. */
val LocalAppContainer = compositionLocalOf<AppContainer> { error("No AppContainer provided") }

/** The current UI locale, observed so text re-formats when the configuration changes. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** A formatter for [currency] in the current UI locale, kept until either changes. */
@Composable
fun rememberMoneyFormatter(currency: CurrencySpec): MoneyFormatter {
    val locale = currentLocale()
    return remember(currency, locale) { MoneyFormatter(currency, locale) }
}

/**
 * A formatter for the currency [currencyCode] (such as a stored sale's), in the current UI locale.
 *
 * @throws IllegalArgumentException if Adyen does not support [currencyCode].
 */
@Composable
fun rememberMoneyFormatter(currencyCode: String): MoneyFormatter =
    rememberMoneyFormatter(remember(currencyCode) { CurrencySpec.of(currencyCode) })

/**
 * Money formatters in one UI [locale], each currency's created once, for lists that mix currencies (history days and
 * rows) without building one per row on every recomposition. Use from the main thread only, as composition does.
 */
@Stable
class MoneyFormatters(
    private val locale: Locale,
) {
    private val byCode = HashMap<String, MoneyFormatter>()

    /**
     * The formatter for [currencyCode], such as a stored sale's.
     *
     * @throws IllegalArgumentException if Adyen does not support [currencyCode].
     */
    operator fun get(currencyCode: String): MoneyFormatter =
        byCode.getOrPut(currencyCode) { MoneyFormatter(CurrencySpec.of(currencyCode), locale) }
}

/** [MoneyFormatters] for the current UI locale, kept until it changes. */
@Composable
fun rememberMoneyFormatters(): MoneyFormatters {
    val locale = currentLocale()
    return remember(locale) { MoneyFormatters(locale) }
}

/**
 * Thin banner under the top bar when payments are simulated. There is none for the TEST environment: Adyen's test
 * terminals already say TEST in their own status bar, and none while the website's screenshots are captured
 * ([app.minimpos.app.terminal.DeviceInfo.docsCapture]).
 */
@Composable
fun ModeBanner(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    if (terminal.mode != TerminalMode.SIMULATOR || container.device.docsCapture) return
    val colors = LocalStatusColors.current
    val compact = LocalDimens.current.compact
    Text(
        text = stringResource(R.string.banner_simulator),
        color = colors.warning,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.warningContainer)
                .padding(vertical = if (compact) 2.dp else 4.dp)
                .testTag("modeBanner"),
    )
}

/** The key [NumericKeypad] and the PIN pad use for backspace. */
const val KEY_BACKSPACE = "<"

/**
 * A 3-column keypad of [keys] (read row by row), each tagged `<tagPrefix>_<key>`. [keyContent] draws a key: by default
 * [KEY_BACKSPACE] as the backspace icon and any other key as its text. Only keys [enabled] accepts can be pressed.
 * With [fill] the keys share the height the keypad is given (e.g. through a weight) instead of having the theme's key
 * height.
 */
@Composable
fun Keypad(
    keys: List<String>,
    onKey: (String) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    enabled: (String) -> Boolean = { true },
    keyContent: @Composable (key: String) -> Unit = { KeyLabel(it) },
) {
    val dimens = LocalDimens.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimens.keySpacing)) {
        keys.chunked(KEYPAD_COLUMNS).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.keySpacing),
                modifier = if (fill) Modifier.weight(1f) else Modifier,
            ) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = { onKey(key) },
                        enabled = enabled(key),
                        modifier =
                            Modifier
                                .weight(1f)
                                .then(if (fill) Modifier.fillMaxHeight() else Modifier.heightIn(min = dimens.keyHeight))
                                .testTag("${tagPrefix}_$key"),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = if (fill) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
                    ) { keyContent(key) }
                }
            }
        }
    }
}

/** The default look of a [Keypad] key: the backspace icon for [KEY_BACKSPACE], else [key] as large text. */
@Composable
fun KeyLabel(
    key: String,
    modifier: Modifier = Modifier,
) {
    if (key == KEY_BACKSPACE) {
        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.action_backspace), modifier = modifier)
    } else {
        Text(key, style = MaterialTheme.typography.headlineSmall, modifier = modifier)
    }
}

/**
 * Cash-register keypad: digits, double zero and backspace, keys tagged `key_<key>`. With [fill] the keys share the
 * height the keypad is given (e.g. through a weight) instead of having a fixed height.
 */
@Composable
fun NumericKeypad(
    onDigit: (Int) -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) = Keypad(
    keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", KEY_BACKSPACE),
    onKey = { key ->
        when (key) {
            KEY_BACKSPACE -> onBackspace()
            "00" -> onDoubleZero()
            else -> onDigit(key.toInt())
        }
    },
    tagPrefix = "key",
    modifier = modifier,
    fill = fill,
)

private const val KEYPAD_COLUMNS = 3

/** Renders QR runs encoded on [cpu]; [nextContent] optionally prefetches one upcoming animated code. */
@Composable
fun QrImage(
    content: String,
    modifier: Modifier = Modifier,
    cpu: CoroutineDispatcher = Dispatchers.Default,
    nextContent: String? = null,
) {
    val cache = remember(cpu) { QrShapes() }
    val shape by produceState<QrCodes.Shape?>(null, content, nextContent, cpu) {
        // A prefetched code shows at once; otherwise nothing is shown until it is encoded, never the previous code.
        value = cache.cached(content)
        if (value == null) value = withContext(cpu) { cache.get(content) }
        if (nextContent != null && nextContent != content) withContext(cpu) { cache.get(nextContent) }
    }
    val paint =
        remember {
            Paint().apply {
                color = AndroidColor.BLACK
                isAntiAlias = false
            }
        }
    Canvas(modifier = modifier.aspectRatio(1f).background(Color.White)) {
        shape?.let { code ->
            val cell = size.minDimension / code.width
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.withScale(cell, cell) { drawPath(code.path, paint) }
            }
        }
    }
}

/**
 * The same fixed-width slip used for sharing, scaled to the available screen width without reflowing its rows.
 * Layout measurement runs on [cpu]; drawing uses no full-height bitmap. Receipt text remains available to
 * accessibility services in document order, including QR captions and link URLs.
 */
@Composable
fun ReceiptPreview(
    document: ReceiptDocument,
    modifier: Modifier = Modifier,
    cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    val prepared by produceState<Pair<ReceiptImage.MeasuredReceipt, List<AnnotatedString>>?>(null, document, cpu) {
        value = null
        value =
            withContext(cpu) {
                val text =
                    document.elements.flatMap { element ->
                        when (element) {
                            is ReceiptElement.Text -> listOf(element.text)
                            is ReceiptElement.Row -> listOf(element.left, element.right)
                            is ReceiptElement.Link -> listOf(element.url)
                            is ReceiptElement.Qr -> listOfNotNull(element.caption)
                            ReceiptElement.Blank, ReceiptElement.Divider -> emptyList()
                        }.map(::AnnotatedString)
                    }
                ReceiptImage.measure(document) to text
            }
    }
    val (slip, receiptText) = prepared ?: return
    val viewport = remember { mutableStateOf<Rect?>(null) }
    Canvas(
        modifier =
            modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(slip.width.toFloat() / slip.height)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .semantics { this[SemanticsProperties.Text] = receiptText }
                .onGloballyPositioned { coordinates ->
                    val visible = coordinates.boundsInWindow()
                    viewport.value =
                        if (visible.isEmpty) {
                            Rect.Zero
                        } else {
                            Rect(
                                coordinates.windowToLocal(visible.topLeft),
                                coordinates.windowToLocal(visible.bottomRight),
                            )
                        }
                }.testTag("receiptImage"),
    ) {
        drawIntoCanvas { canvas ->
            val visible = viewport.value ?: return@drawIntoCanvas
            // Ancestor scroll layers can clip after recording; pass their visible bounds into the native drawing too.
            canvas.nativeCanvas.withClip(visible.left, visible.top, visible.right, visible.bottom) {
                withScale(size.width / slip.width, size.width / slip.width) { slip.draw(this) }
            }
        }
    }
}

/** What the simulated printer received, using the same fixed-width slip as receipt previews and sharing. */
@Composable
fun PrintJobsPreview(
    jobs: List<PrintJob>,
    modifier: Modifier = Modifier,
) {
    val document =
        remember(jobs) {
            ReceiptDocument(
                jobs.flatMap { job ->
                    when (job) {
                        is PrintJob.QrCode -> {
                            listOf(ReceiptElement.Qr(job.content))
                        }

                        is PrintJob.Text -> {
                            job.lines.map { line ->
                                when (line) {
                                    is PrintLine.Columns -> {
                                        ReceiptElement.Row(line.left, line.right, style(line.style))
                                    }

                                    is PrintLine.Text -> {
                                        when {
                                            line.text.length >= MIN_RULE_DASHES && line.text.all { it == '-' } -> ReceiptElement.Divider
                                            line.text.isEmpty() -> ReceiptElement.Blank
                                            else -> ReceiptElement.Text(line.text, align(line.align), style(line.style))
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    ReceiptPreview(document, modifier)
}

/** The fewest dashes a printed line needs to count as a divider rather than text. */
private const val MIN_RULE_DASHES = 3

private fun style(value: PrintStyle) =
    when (value) {
        PrintStyle.NORMAL -> TextStyle.NORMAL
        PrintStyle.BOLD -> TextStyle.BOLD
        PrintStyle.UNDERLINE -> TextStyle.UNDERLINE
    }

private fun align(value: PrintAlign) =
    when (value) {
        PrintAlign.LEFT -> Align.LEFT
        PrintAlign.CENTER -> Align.CENTER
        PrintAlign.RIGHT -> Align.RIGHT
    }
