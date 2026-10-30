package app.minimpos.app

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** How many pixels of a screenshot may differ from its golden before the comparison fails. */
enum class ScreenshotTolerance(
    val changeThreshold: Float,
) {
    /** Component gallery: any changed pixel fails, since a component's rendering is fully deterministic. */
    EXACT(0f),

    /** Full screens: 0.1 % of the pixels, for anti-aliasing that differs between JVMs and operating systems. */
    SCREEN(0.001f),
}

/**
 * Compares the Compose test rule's content with golden images under `app/src/test/screenshots/`, or records them.
 * Rule order 2, after `TestEnvironment` (0) and [createRecordingComposeRule] (1), which the rule wraps.
 *
 * Which of the two happens is chosen by the Gradle task (`verifyRoborazziDebug`, `recordRoborazziDebug`); an ordinary
 * test run captures nothing. Images are named `<test class>/<test method>[-<variant>]_<device>.png` after the running
 * test and the configured window size, so a test needs no names of its own.
 *
 * It makes the rendering reproducible: UTC time zone, and it fails the test unless the Robolectric configuration has the
 * expected [locale] (`en-AU`, whose amounts are the app's `en-rAU`) and [fontScale] rather than silently drawing
 * something else. [setContent] freezes the Compose clock so no animation moves between the setup and the capture; a
 * test that drives the app calls [capture] once the screen has settled, which freezes the clock from there.
 */
class ScreenshotRule(
    private val compose: ComposeContentTestRule,
    private val tolerance: ScreenshotTolerance,
    private val locale: Locale = Locale.forLanguageTag("en-AU"),
    private val fontScale: Float = 1f,
) : TestWatcher() {
    private lateinit var test: Description
    private lateinit var zone: TimeZone

    override fun starting(description: Description) {
        test = description
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    override fun finished(description: Description) {
        TimeZone.setDefault(zone)
    }

    /**
     * Shows [content] with the clock frozen, then lets it settle for a fixed [SETTLE_MILLIS] so that animations such
     * as a spinner stand at the same frame in every run.
     */
    fun setContent(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent(content)
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    /**
     * Captures the root of the Compose content, or with [screen] every window of the screen, such as a dialog above
     * it. [variant] tells apart several captures of one test.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    fun capture(
        variant: String? = null,
        screen: Boolean = false,
    ) {
        val configuration = RuntimeEnvironment.getApplication().resources.configuration
        check(configuration.locales[0].toLanguageTag() == locale.toLanguageTag()) {
            "Screenshots use $locale, but this test runs in ${configuration.locales[0]}"
        }
        check(configuration.fontScale == fontScale) { "Screenshots use font scale $fontScale, not ${configuration.fontScale}" }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        val file = File(requireNotNull(System.getProperty("minimpos.screenshotDirectory")), imageName(configuration, variant))
        val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = tolerance.changeThreshold))
        if (screen) captureScreenRoboImage(file, options) else compose.onRoot().captureRoboImage(file, options)
    }

    private fun imageName(
        configuration: Configuration,
        variant: String?,
    ): String {
        val method = slug(test.methodName.orEmpty())
        val device =
            if (configuration.screenWidthDp == AMS1_WIDTH && configuration.screenHeightDp == AMS1_HEIGHT) {
                "ams1"
            } else {
                "${configuration.screenWidthDp}x${configuration.screenHeightDp}dp"
            }
        return "${test.testClass.simpleName}/$method${variant?.let { "-${slug(it)}" }.orEmpty()}_$device.png"
    }

    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        /** Enough frames for any entrance animation to end; fixed so that endless ones stop at the same point. */
        const val SETTLE_MILLIS = 1_000L
        const val AMS1_WIDTH = 320
        const val AMS1_HEIGHT = 460
    }
}
