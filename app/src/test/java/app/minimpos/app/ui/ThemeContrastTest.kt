package app.minimpos.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.minimpos.app.ui.theme.MiniMposColors
import app.minimpos.app.ui.theme.StatusColors
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * WCAG 2.2 contrast of the colour pairs the screens draw: text needs 4.5:1 (1.4.3), outlines and icons that carry
 * meaning 3:1 (1.4.11).
 */
class ThemeContrastTest {
    private val scheme = MiniMposColors
    private val status = StatusColors()

    @Test
    fun `text is legible on the backgrounds it is drawn on`() {
        mapOf(
            "on primary (buttons, payment tiles, badges)" to (scheme.onPrimary to scheme.primary),
            "primary on surface (text buttons, links)" to (scheme.primary to scheme.surface),
            "on secondary" to (scheme.onSecondary to scheme.secondary),
            "on surface" to (scheme.onSurface to scheme.surface),
            "secondary text on surface" to (scheme.onSurfaceVariant to scheme.surface),
            "secondary text on cards" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "on primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "error on surface" to (scheme.error to scheme.surface),
            "on error" to (scheme.onError to scheme.error),
            "on error container" to (scheme.onErrorContainer to scheme.errorContainer),
            "dialog text" to (scheme.onSurface to scheme.surfaceContainerHigh),
            "secondary text in dialogs" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
            "success text" to (status.success to scheme.surface),
            "warning text" to (status.warning to scheme.surface),
            "simulator banner" to (status.warning to status.warningContainer),
            "warning notice text" to (scheme.onSurface to status.warningContainer),
            "notice text on cards" to (scheme.onSurface to scheme.surfaceVariant),
            "error text on its container" to (status.error to status.errorContainer),
            "mark on success" to (status.onStatus to status.success),
            "mark on warning" to (status.onStatus to status.warning),
            "mark on error" to (status.onStatus to status.error),
        ).forEach { (use, pair) -> assertWithMessage(use).that(contrast(pair.first, pair.second)).isAtLeast(TEXT) }
    }

    @Test
    fun `outlines and meaningful icons stand out`() {
        mapOf(
            "field and secondary-button outlines" to (scheme.outline to scheme.surface),
            "icons on tiles" to (scheme.primary to scheme.surfaceVariant),
            "outline of a product in the cart" to (scheme.primary to scheme.primaryContainer),
            "warning icons" to (status.warning to scheme.surface),
            "warning notice icons" to (status.warning to status.warningContainer),
            "info notice icons" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        ).forEach { (use, pair) -> assertWithMessage(use).that(contrast(pair.first, pair.second)).isAtLeast(GRAPHICS) }
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        return (max(a, b) + FLARE) / (min(a, b) + FLARE)
    }

    private companion object {
        const val TEXT = 4.5
        const val GRAPHICS = 3.0
        const val FLARE = 0.05
    }
}
