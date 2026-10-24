package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The Kotlin/JVM modules share one setup (compiler options, detekt, Android Lint, Dokka, PIT) through build-logic's
 * convention plugins, so a module that copies part of it instead would drift when the shared setup changes. :app is an
 * Android module with its own setup.
 */
class BuildConventionTest {
    private val repository = Path.of(System.getProperty("minimpos.repository"))

    @Test
    fun `Kotlin JVM modules take their shared setup from the convention plugins`() {
        val scripts =
            Files.list(repository).use { entries ->
                entries
                    .filter { it.isDirectory() && it.name != "app" && it.name != "build-logic" }
                    .map { it.resolve("build.gradle.kts") }
                    .filter(Files::exists)
                    .toList()
            }
        assertThat(scripts.map { it.parent.name }).containsAtLeast("core", "adyen", "tooling", "website-test")
        for (script in scripts) {
            assertWithMessage(script.parent.name).that(violations(Files.readString(script))).isEmpty()
        }
    }

    @Test
    fun `convention guard rejects a module repeating the shared setup`() {
        val script =
            """
            plugins {
                alias(libs.plugins.kotlin.jvm)
                alias(libs.plugins.detekt)
            }

            kotlin {
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }
            """.trimIndent()
        assertThat(violations(script)).containsExactly(
            "applies no minimpos.kotlin-jvm convention plugin",
            "repeats libs.plugins.kotlin.jvm",
            "repeats libs.plugins.detekt",
            "repeats allWarningsAsErrors",
        )
    }

    private companion object {
        // Plugins and settings the convention plugins own.
        val shared =
            listOf(
                "libs.plugins.kotlin.jvm",
                "libs.plugins.detekt",
                "libs.plugins.kover",
                "libs.plugins.android.lint",
                "libs.plugins.dokka",
                "libs.plugins.pitest",
                "sourceCompatibility",
                "jvmTarget",
                "allWarningsAsErrors",
                "config/detekt/detekt.yml",
                "failOnWarning",
                "warningsAsErrors",
                "outputFormats",
            )

        fun violations(script: String): List<String> =
            listOfNotNull("applies no minimpos.kotlin-jvm convention plugin".takeIf { "id(\"minimpos.kotlin-jvm" !in script }) +
                shared.filter { it in script }.map { "repeats $it" }
    }
}
