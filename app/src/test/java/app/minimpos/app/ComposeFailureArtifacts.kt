package app.minimpos.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.printToString
import androidx.core.graphics.createBitmap
import org.junit.internal.AssumptionViolatedException
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import androidx.compose.ui.test.junit4.v2.createComposeRule as platformComposeRule

internal fun createRecordingComposeRule(): ComposeContentTestRule = RecordingComposeRule(platformComposeRule())

internal class RecordingComposeRule(
    private val delegate: ComposeContentTestRule,
    private val capture: (ComposeContentTestRule, Description) -> Unit = { rule, test ->
        captureComposeFailure(rule, test, Paths.get(requireNotNull(System.getProperty("minimpos.uiFailureDirectory"))))
    },
) : ComposeContentTestRule by delegate {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        delegate.apply(
            recordComposeFailures(base, description) { test, _ ->
                capture(delegate, test)
            },
            description,
        )
}

internal fun recordComposeFailures(
    base: Statement,
    description: Description,
    capture: (Description, Throwable) -> Unit,
): Statement =
    object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (failure: Throwable) {
                if (failure is AssumptionViolatedException) throw failure
                try {
                    capture(description, failure)
                } catch (captureFailure: Throwable) {
                    if (captureFailure !== failure) failure.addSuppressed(captureFailure)
                }
                throw failure
            }
        }
    }

@OptIn(ExperimentalTestApi::class)
internal fun captureComposeFailure(
    compose: ComposeContentTestRule,
    description: Description,
    root: Path,
): Path {
    Files.createDirectories(root)
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
    val directory = Files.createTempDirectory(root, "failure-")
    val configuration = RuntimeEnvironment.getApplication().resources.configuration
    directory.resolve("environment.txt").toFile().writeText(
        "${description.className} > ${description.methodName}\n" +
            "${configuration.screenWidthDp}x${configuration.screenHeightDp} dp, density=${configuration.densityDpi}\n" +
            "locale=${configuration.locales.toLanguageTags()}, SDK=${Build.VERSION.SDK_INT}, timestamp=${Instant.now()}\n" +
            "Synthetic test data only; not a merchant-device screenshot.\n",
    )
    compose.runWithoutImplicitWait {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val node = roots[index]
            directory.resolve("root-$index.txt").toFile().writeText(node.printToString())
            runCatching {
                val view = checkNotNull(node.fetchSemanticsNode().root as? ViewRootForTest).view
                val bitmap =
                    compose.runOnUiThread {
                        createBitmap(view.width, view.height).also { view.draw(Canvas(it)) }
                    }
                Files.newOutputStream(directory.resolve("root-$index.png")).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG capture failed" }
                }
            }.onFailure { failure ->
                directory.resolve("root-$index-error.txt").toFile().writeText(failure.stackTraceToString())
            }
        }
    }
    return directory
}
