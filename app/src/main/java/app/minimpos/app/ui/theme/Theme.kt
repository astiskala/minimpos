package app.minimpos.app.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette modelled on the Adyen terminal payment UI: white surfaces, Adyen green actions, dark navy text. */
object AdyenColors {
    /** Adyen green: primary actions and success. */
    val Green = Color(0xFF0ABF53)

    /** Light green background of primary containers. */
    val GreenLight = Color(0xFFE6F8EE)

    /** Dark navy: text and secondary actions. */
    val Navy = Color(0xFF00112C)

    /** Secondary text. */
    val Grey = Color(0xFF5C687C)

    /** Light grey surfaces, such as cards and fields. */
    val GreyLight = Color(0xFFF3F6F9)

    /** Dividers and card borders. */
    val Border = Color(0xFFE3E8EE)

    /** Field outlines. */
    val Outline = Color(0xFF8F9AAA)

    /** Errors and declines. */
    val Red = Color(0xFFD10244)

    /** Light red background of error containers. */
    val RedLight = Color(0xFFFBE6ED)

    /** Warnings, such as unknown outcomes and the TEST banner. */
    val Orange = Color(0xFFE56A00)

    /** Light orange background of warning containers. */
    val OrangeLight = Color(0xFFFFF1E5)

    /** Links and tertiary accents. */
    val Blue = Color(0xFF0070F5)
}

/**
 * Colours for outcomes, which Material's scheme has no slots for (it only has `error`).
 *
 * @property success Approved payments and completed actions.
 * @property warning Unknown or pending outcomes.
 * @property warningContainer Background behind [warning] content.
 * @property error Declines and failures.
 * @property errorContainer Background behind [error] content.
 */
@Immutable
data class StatusColors(
    val success: Color = AdyenColors.Green,
    val warning: Color = AdyenColors.Orange,
    val warningContainer: Color = AdyenColors.OrangeLight,
    val error: Color = AdyenColors.Red,
    val errorContainer: Color = AdyenColors.RedLight,
)

/** The [StatusColors] of the current theme. */
val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

private val colors =
    lightColorScheme(
        primary = AdyenColors.Green,
        onPrimary = Color.White,
        primaryContainer = AdyenColors.GreenLight,
        onPrimaryContainer = AdyenColors.Navy,
        secondary = AdyenColors.Navy,
        onSecondary = Color.White,
        secondaryContainer = AdyenColors.GreyLight,
        onSecondaryContainer = AdyenColors.Navy,
        tertiary = AdyenColors.Blue,
        background = Color.White,
        onBackground = AdyenColors.Navy,
        surface = Color.White,
        onSurface = AdyenColors.Navy,
        surfaceVariant = AdyenColors.GreyLight,
        onSurfaceVariant = AdyenColors.Grey,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = AdyenColors.GreyLight,
        surfaceContainerHigh = AdyenColors.GreyLight,
        surfaceContainerHighest = AdyenColors.Border,
        outline = AdyenColors.Outline,
        outlineVariant = AdyenColors.Border,
        error = AdyenColors.Red,
        onError = Color.White,
        errorContainer = AdyenColors.RedLight,
        onErrorContainer = AdyenColors.Red,
    )

private val base = Typography()

private val typography =
    base.copy(
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    )

private val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** Large amount text used on checkout and result screens, like the terminal's amount display. */
val AmountTextStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)

/**
 * Sizes for the screen the app runs on, from the AMS1's 4" 480×800 px display (about 320×533 dp, of which the status
 * bar, Adyen's navigation bar and the app bar leave roughly 370 dp for content) to the 6.7" S1F4 Pro.
 */
@Immutable
data class Dimens(
    /**
     * Whether the height is limited ([Compact] and [Medium]): screens then switch to layouts that keep their main action
     * in view without scrolling, such as the full-screen keypad, and leave out decoration.
     */
    val compact: Boolean,
    /** Padding around screen content. */
    val screenPadding: Dp,
    /** Space between stacked items. */
    val spacing: Dp,
    /** Vertical padding of list and settings rows. */
    val rowPadding: Dp,
    /** Height of primary buttons. */
    val buttonHeight: Dp,
    /** Height of secondary (outlined) buttons. */
    val secondaryButtonHeight: Dp,
    /** Height of keypad keys (amount and PIN entry). */
    val keyHeight: Dp,
    /** Gap between keypad keys. */
    val keySpacing: Dp,
    /** Size of the round status badge on result screens. */
    val badgeSize: Dp,
    /** Height of the top app bar. */
    val topBarHeight: Dp,
    /** Style of large amounts, as on the terminal's amount display. */
    val amountStyle: TextStyle,
    /** Padding inside cards and tiles. */
    val cardPadding: Dp,
    /** Minimum height of the product tiles on the sale screen: two lines of name and the price. */
    val tileHeight: Dp,
    /** Style of the top app bar's title. */
    val titleStyle: TextStyle,
    /** Style of the outcome ("Approved", "Refund requested") on result and detail screens. */
    val outcomeStyle: TextStyle,
    /** Size of the spinner while the terminal handles a transaction. */
    val progressSize: Dp,
) {
    /** The size sets and how one is chosen. */
    companion object {
        /** Sizes for phones and terminals with room to spare, such as the S1E4 Pro and S1F4 Pro. */
        val Regular =
            Dimens(
                compact = false,
                screenPadding = 16.dp,
                spacing = 12.dp,
                rowPadding = 12.dp,
                buttonHeight = 56.dp,
                secondaryButtonHeight = 52.dp,
                keyHeight = 56.dp,
                keySpacing = 8.dp,
                badgeSize = 88.dp,
                topBarHeight = 64.dp,
                amountStyle = AmountTextStyle,
                cardPadding = 16.dp,
                tileHeight = 96.dp,
                titleStyle = typography.titleLarge,
                outcomeStyle = typography.headlineMedium,
                progressSize = 64.dp,
            )

        /**
         * Sizes for 5.5" terminals such as the S1F2 (360×640 dp, about 570 dp between the system bars):
         * the [Compact] layouts, with more generous sizes.
         */
        val Medium =
            Dimens(
                compact = true,
                screenPadding = 16.dp,
                spacing = 10.dp,
                rowPadding = 10.dp,
                buttonHeight = 52.dp,
                secondaryButtonHeight = 48.dp,
                keyHeight = 52.dp,
                keySpacing = 8.dp,
                badgeSize = 64.dp,
                topBarHeight = 56.dp,
                amountStyle = AmountTextStyle.copy(fontSize = 38.sp),
                cardPadding = 14.dp,
                tileHeight = 88.dp,
                titleStyle = typography.titleLarge,
                outcomeStyle = typography.headlineSmall,
                progressSize = 56.dp,
            )

        /** Sizes for small screens such as the AMS1, where the primary actions must fit without scrolling. */
        val Compact =
            Dimens(
                compact = true,
                screenPadding = 12.dp,
                spacing = 8.dp,
                rowPadding = 8.dp,
                buttonHeight = 48.dp,
                secondaryButtonHeight = 44.dp,
                keyHeight = 44.dp,
                keySpacing = 6.dp,
                badgeSize = 56.dp,
                topBarHeight = 48.dp,
                amountStyle = AmountTextStyle.copy(fontSize = 34.sp),
                cardPadding = 12.dp,
                tileHeight = 80.dp,
                titleStyle = typography.titleMedium,
                outcomeStyle = typography.headlineSmall,
                progressSize = 48.dp,
            )

        /**
         * The sizes for a window whose area between the system bars is [width] × [height]: [Compact] below 360 dp of
         * width or 520 dp of height (AMS1, P630, landscape phones), [Medium] below 640 dp of height (S1F2),
         * otherwise [Regular].
         */
        fun forWindow(
            width: Dp,
            height: Dp,
        ): Dimens =
            when {
                width < 360.dp || height < 520.dp -> Compact
                height < 640.dp -> Medium
                else -> Regular
            }
    }
}

/** The [Dimens] for the current window, provided by [MiniMposTheme]. */
val LocalDimens = staticCompositionLocalOf { Dimens.Regular }

/**
 * Material 3 with the Adyen-like palette, typography and shapes, and [LocalDimens] chosen for the space between the
 * system bars (the app draws edge to edge, so the window itself includes them).
 */
@Composable
fun MiniMposTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        BoxWithConstraints {
            val bars = WindowInsets.systemBars.asPaddingValues()
            val direction = LocalLayoutDirection.current
            val width = maxWidth - bars.calculateStartPadding(direction) - bars.calculateEndPadding(direction)
            val height = maxHeight - bars.calculateTopPadding() - bars.calculateBottomPadding()
            CompositionLocalProvider(LocalDimens provides Dimens.forWindow(width, height), content = content)
        }
    }
}
