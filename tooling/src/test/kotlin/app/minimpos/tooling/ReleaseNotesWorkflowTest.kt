package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReleaseNotesWorkflowTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workflow = Files.readString(Path.of(System.getProperty("minimpos.repository"), ".github/workflows/release.yml"))

    @Test
    fun `blank notes stop release preparation while Markdown is accepted`() {
        val script = stepScript("Require authored release notes before preparing a version")
        for (notes in listOf("", " ", "\n\t\r")) {
            assertThat(run(script, mapOf("RELEASE_NOTES" to notes))).isEqualTo(1)
        }
        assertThat(run(script, mapOf("RELEASE_NOTES" to "- Fixed receipt wrapping.\n- Added a language."))).isEqualTo(0)
        assertThat(workflow.indexOf("Require authored release notes")).isLessThan(workflow.indexOf("Prepare the version commit"))
    }

    @Test
    fun `publication preserves Markdown as literal text and appends the release comparison`() {
        val root = temporary.newFolder().toPath()
        val marker = root.resolve("executed")
        val notes = "- Keep `code`, quotes \"like this\", and \$values.\n- Literal \$(touch $marker) and %s."
        val stubs =
            """
            java() { return 0; }
            gh() { printf '%s\n' "${'$'}@" > "${'$'}RUNNER_TEMP/arguments"; }
            """.trimIndent()
        assertThat(
            run(
                stubs + "\n" + stepScript("Publish the GitHub Release"),
                mapOf(
                    "RUNNER_TEMP" to root.toString(),
                    "RELEASE_NOTES" to notes,
                    "VERSION" to "1.6.0",
                    "PREVIOUS_TAG" to "v1.5.2",
                    "REPOSITORY_URL" to "https://github.com/astiskala/minimpos",
                ),
            ),
        ).isEqualTo(0)
        assertThat(Files.readString(root.resolve("release-notes.md"))).isEqualTo(
            "$notes\n\n**Full Changelog**: https://github.com/astiskala/minimpos/compare/v1.5.2...v1.6.0\n",
        )
        val arguments = Files.readAllLines(root.resolve("arguments"))
        assertThat(arguments).containsAtLeast("release", "create", "v1.6.0", "--notes-file", root.resolve("release-notes.md").toString())
        assertThat(arguments).doesNotContain("--generate-notes")
        assertThat(Files.exists(marker)).isFalse()
    }

    private fun stepScript(name: String): String =
        workflow
            .substringAfter("- name: $name\n")
            .substringAfter("        run: |\n")
            .lineSequence()
            .takeWhile { it.startsWith("          ") || it.isBlank() }
            .joinToString("\n")
            .trimIndent()

    private fun run(
        script: String,
        environment: Map<String, String>,
    ): Int {
        val builder = ProcessBuilder("bash", "-eu", "-c", script).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor()
    }
}
