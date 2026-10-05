package io.github.astiskala.minimpos.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.astiskala.minimpos.app.AppContainer
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.qr.QrCodes
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.app.ui.theme.LocalStatusColors
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.receipt.Align
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.receipt.ReceiptElement
import io.github.astiskala.minimpos.core.receipt.TextStyle
import io.github.astiskala.minimpos.terminal.client.PrintAlign
import io.github.astiskala.minimpos.terminal.client.PrintJob
import io.github.astiskala.minimpos.terminal.client.PrintLine
import io.github.astiskala.minimpos.terminal.client.PrintStyle
import java.util.Locale

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
 * Opens [url] in the browser, or whatever app answers it; does nothing when the device has none (an Adyen terminal
 * has no browser).
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (ignored: ActivityNotFoundException) {
    }
}

/**
 * Thin banner under the top bar when payments are simulated. There is none for the TEST environment: Adyen's test
 * terminals already say TEST in their own status bar.
 */
@Composable
fun ModeBanner(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    if (terminal.mode != TerminalMode.SIMULATOR) return
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

/** Renders a QR code crisply at any size. */
@Composable
fun QrImage(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { QrCodes.matrix(content, margin = 2) }
    Canvas(modifier = modifier.aspectRatio(1f).background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // Each module overlaps its neighbours by half a pixel, so fractional cell sizes leave no hairline gaps.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** A receipt drawn as a paper slip, for previews and the history screen. */
@Composable
fun ReceiptPreview(
    document: ReceiptDocument,
    modifier: Modifier = Modifier,
) {
    Paper(modifier) {
        document.elements.forEach { element ->
            when (element) {
                is ReceiptElement.Text -> {
                    PaperText(element.text, element.align, element.style)
                }

                is ReceiptElement.Row -> {
                    Row(Modifier.fillMaxWidth()) {
                        PaperText(element.left, Align.LEFT, element.style, Modifier.weight(1f))
                        PaperText(element.right, Align.RIGHT, element.style)
                    }
                }

                ReceiptElement.Divider -> {
                    Canvas(Modifier.fillMaxWidth().height(16.dp).testTag("receiptDivider")) {
                        drawLine(
                            Color.Black,
                            Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                }

                ReceiptElement.Blank -> {
                    Spacer(Modifier.height(10.dp))
                }

                is ReceiptElement.Link -> {
                    PaperText(element.url, Align.CENTER, TextStyle.NORMAL)
                }

                is ReceiptElement.Qr -> {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(element.content, Modifier.size(140.dp))
                        element.caption?.let { PaperText(it, Align.CENTER, TextStyle.NORMAL) }
                    }
                }
            }
        }
    }
}

/** What the simulated printer received, shown as the slip a real terminal would print. */
@Composable
fun PrintJobsPreview(
    jobs: List<PrintJob>,
    modifier: Modifier = Modifier,
) {
    Paper(modifier) {
        jobs.forEach { job ->
            when (job) {
                is PrintJob.QrCode -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        QrImage(job.content, Modifier.size(140.dp))
                    }
                }

                is PrintJob.Text -> {
                    job.lines.forEach { PrintedLine(it) }
                }
            }
        }
    }
}

@Composable
private fun PrintedLine(line: PrintLine) {
    when (line) {
        is PrintLine.Columns -> {
            Row(Modifier.fillMaxWidth()) {
                PaperText(line.left, Align.LEFT, style(line.style), Modifier.weight(1f))
                PaperText(line.right, Align.RIGHT, style(line.style))
            }
        }

        is PrintLine.Text -> {
            PaperText(line.text, align(line.align), style(line.style))
        }
    }
}

@Composable
private fun Paper(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val compact = LocalDimens.current.compact
    Surface(
        modifier =
            modifier
                .widthIn(
                    max = 360.dp,
                ).fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
        color = Color.White,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 12.dp else 20.dp)) { content() }
    }
}

@Composable
private fun PaperText(
    text: String,
    align: Align,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (style == TextStyle.BOLD) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (style == TextStyle.UNDERLINE) TextDecoration.Underline else null,
        textAlign =
            when (align) {
                Align.LEFT -> TextAlign.Start
                Align.CENTER -> TextAlign.Center
                Align.RIGHT -> TextAlign.End
            },
        modifier = if (align == Align.RIGHT) modifier else modifier.fillMaxWidth(),
    )
}

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
