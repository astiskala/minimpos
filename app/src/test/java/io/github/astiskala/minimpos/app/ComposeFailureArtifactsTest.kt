package io.github.astiskala.minimpos.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.get
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ComposeFailureArtifactsTest {
    @get:Rule(order = 0)
    val temporary = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `captures unmerged tree PNG and locale dimensions without filename collisions`() {
        compose.setContent { Text("Synthetic simulator demo") }
        val root = temporary.newFolder().toPath()
        val description = Description.createTestDescription(javaClass, "failure / with unsafe filename")
        val first = captureComposeFailure(compose, description, root)
        val second = captureComposeFailure(compose, description, root)
        assertThat(first).isNotEqualTo(second)
        assertThat(first.resolve("root-0.txt").toFile().readText()).contains("Synthetic simulator demo")
        val image = BitmapFactory.decodeFile(first.resolve("root-0.png").toString())
        assertThat(image).isNotNull()
        assertThat(image.width).isGreaterThan(0)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertThat(pixels.toSet().size).isGreaterThan(1)
        val metadata = first.resolve("environment.txt").toFile().readText()
        assertThat(metadata).contains("320x460 dp")
        assertThat(metadata).contains("en-AU")
    }

    @Test
    fun `dialog PNG captures its own window not underlying activity`() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Red)) { Text("Background demo") }
            Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Blue)) { Text("Dialog demo") }
            }
        }
        val root = temporary.newFolder().toPath()
        val directory = captureComposeFailure(compose, Description.createTestDescription(javaClass, "dialog"), root)
        val trees =
            Files.list(directory).use { files ->
                files
                    .iterator()
                    .asSequence()
                    .filter { it.fileName.toString().matches(Regex("root-[0-9]+\\.txt")) }
                    .toList()
            }
        val dialog = trees.single { it.toFile().readText().contains("Dialog demo") }
        val image = BitmapFactory.decodeFile(dialog.toString().removeSuffix(".txt") + ".png")
        assertThat(image[image.width / 2, image.height / 2]).isEqualTo(Color.Blue.toArgb())
    }

    @Test
    fun `recording runs inside delegate lifecycle before teardown`() {
        val events = mutableListOf<String>()
        val delegate =
            object : ComposeContentTestRule by compose {
                override fun apply(
                    base: Statement,
                    description: Description,
                ) = object : Statement() {
                    override fun evaluate() {
                        events += "setup"
                        try {
                            base.evaluate()
                        } finally {
                            events += "teardown"
                        }
                    }
                }
            }
        val rule = RecordingComposeRule(delegate) { _, _ -> events += "capture" }
        val failure = AssertionError("original")
        val base =
            object : Statement() {
                override fun evaluate(): Nothing = throw failure
            }
        val description = Description.createTestDescription(javaClass, "lifecycle")
        assertThat(assertThrows(AssertionError::class.java) { rule.apply(base, description).evaluate() }).isSameInstanceAs(failure)
        assertThat(events).containsExactly("setup", "capture", "teardown").inOrder()
    }

    @Test
    fun `capture failure never masks original assertion and passing tests do not capture`() {
        val failure = AssertionError("original assertion")
        var captures = 0
        val description = Description.createTestDescription(javaClass, "failure")
        val base =
            object : Statement() {
                override fun evaluate(): Nothing = throw failure
            }
        val statement =
            recordComposeFailures(base, description) { _, _ ->
                captures++
                error("capture unavailable")
            }
        assertThat(assertThrows(AssertionError::class.java) { statement.evaluate() }).isSameInstanceAs(failure)
        assertThat(captures).isEqualTo(1)
        assertThat(failure.suppressed.single().message).isEqualTo("capture unavailable")
        val passing =
            object : Statement() {
                override fun evaluate() = Unit
            }
        recordComposeFailures(passing, description) { _, _ -> captures++ }.evaluate()
        assertThat(captures).isEqualTo(1)
        val skipped =
            object : Statement() {
                override fun evaluate(): Nothing = throw AssumptionViolatedException("skipped")
            }
        assertThrows(AssumptionViolatedException::class.java) {
            recordComposeFailures(skipped, description) { _, _ -> captures++ }.evaluate()
        }
        assertThat(captures).isEqualTo(1)
    }
}
