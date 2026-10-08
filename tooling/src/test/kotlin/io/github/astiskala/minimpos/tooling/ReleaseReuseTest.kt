package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReleaseReuseTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val commit = "a".repeat(40)

    @Test
    fun `reuse verifies with current tooling without executing the downloaded JAR`() {
        val fixture = fixture()
        artifact(fixture, "123")
        runs(fixture, "123")
        assertThat(execute(fixture)).isEqualTo(0)
        assertThat(Files.readAllLines(fixture.resolve("outputs"))).containsExactly("run=123", "attempt=1")
    }

    @Test
    fun `missing newest artifact falls back to an older successful artifact without polling`() {
        val fixture = fixture()
        artifact(fixture, "123")
        runs(fixture, "124", "123")
        assertThat(execute(fixture)).isEqualTo(0)
        assertThat(Files.readString(fixture.resolve("outputs"))).contains("run=123")
    }

    @Test
    fun `missing all artifacts requests fresh full CI without reuse outputs`() {
        val fixture = fixture()
        runs(fixture, "124", "123")
        assertThat(execute(fixture)).isEqualTo(0)
        assertThat(Files.readString(fixture.resolve("outputs"))).isEmpty()
        assertThat(Files.readString(fixture.resolve("summary"))).contains("the full CI workflow will run")
    }

    @Test
    fun `tampered downloaded artifact fails closed rather than publishing or claiming reuse`() {
        val fixture = fixture()
        val artifact = artifact(fixture, "123")
        Files.writeString(artifact.resolve(Release.APK), "changed APK")
        runs(fixture, "123")
        assertThat(execute(fixture)).isNotEqualTo(0)
        assertThat(Files.readString(fixture.resolve("outputs"))).isEmpty()
        assertThat(Files.readString(fixture.resolve("summary"))).isEmpty()
    }

    @Test
    fun `API failure cannot be mistaken for absent CI`() {
        val fixture = fixture()
        runs(fixture, "123")
        assertThat(execute(fixture, apiExit = 3)).isEqualTo(3)
        assertThat(Files.readString(fixture.resolve("outputs"))).isEmpty()
        assertThat(Files.readString(fixture.resolve("summary"))).isEmpty()
    }

    private fun fixture(): Path {
        val root = temporary.newFolder("release with spaces").toPath()
        val tooling = Files.createDirectories(root.resolve("tooling/build/libs"))
        Files.copy(Path.of(System.getProperty("minimpos.tooling.jar")), tooling.resolve(Release.TOOLING))
        Files.writeString(root.resolve("version.properties"), "versionName=0.6.2\nversionCode=14\n")
        Files.createDirectory(root.resolve("artifacts"))
        Files.createDirectory(root.resolve("runner"))
        Files.createFile(root.resolve("outputs"))
        Files.createFile(root.resolve("summary"))
        val bin = Files.createDirectory(root.resolve("bin"))
        Files.writeString(
            bin.resolve("gh"),
            """#!/bin/bash
            |set -euo pipefail
            |case "${'$'}1:${'$'}2" in
            |  api:--method)
            |    if [ "${'$'}MOCK_API_EXIT" != 0 ]; then exit "${'$'}MOCK_API_EXIT"; fi
            |    cat "${'$'}MOCK_RUNS"
            |    ;;
            |  run:download)
            |    source="${'$'}MOCK_ARTIFACTS/${'$'}3"
            |    if [ ! -d "${'$'}source" ]; then exit 1; fi
            |    destination="${'$'}{!#}"
            |    mkdir -p "${'$'}destination"
            |    cp "${'$'}source/"* "${'$'}destination/"
            |    ;;
            |  *) exit 99 ;;
            |esac
            """.trimMargin(),
        )
        check(bin.resolve("gh").toFile().setExecutable(true))
        val workflow = Files.readString(Path.of(System.getProperty("minimpos.repository")).resolve(".github/workflows/release.yml"))
        val script =
            workflow
                .substringAfter("      - name: Reuse successful exact-commit CI when available\n")
                .substringAfter("        run: |\n")
                .substringBefore("\n  ci:\n")
                .trimIndent()
        check(script.contains("release verify"))
        Files.writeString(root.resolve("reuse.sh"), script)
        return root
    }

    private fun artifact(
        root: Path,
        run: String,
    ): Path {
        val artifact = Files.createDirectory(root.resolve("artifacts/$run"))
        Files.writeString(artifact.resolve(Release.APK), "unsigned APK")
        // Deliberately not executable: prepare must verify hashes using its own current tooling.
        Files.writeString(artifact.resolve(Release.TOOLING), "not a JAR")
        Release.writeJson(
            artifact.resolve(Release.MANIFEST),
            Release.metadata(root.resolve("version.properties"), artifact, commit, run, "1"),
        )
        return artifact
    }

    private fun runs(
        root: Path,
        vararg ids: String,
    ) {
        val runs =
            ids.joinToString(",") {
                """{"id":$it,"run_attempt":1,"head_sha":"$commit","head_branch":"main","event":"push",
                |"status":"completed","conclusion":"success","html_url":"https://github.com/example/project/actions/runs/$it"}
                """.trimMargin()
            }
        Files.writeString(root.resolve("runs.json"), """{"workflow_runs":[$runs]}""")
    }

    private fun execute(
        root: Path,
        apiExit: Int = 0,
    ): Int {
        val process =
            ProcessBuilder("bash", "--noprofile", "--norc", "-eo", "pipefail", root.resolve("reuse.sh").toString())
                .apply {
                    directory(root.toFile())
                    redirectErrorStream(true)
                    redirectOutput(root.resolve("log").toFile())
                    environment().putAll(
                        mapOf(
                            "PATH" to "${root.resolve("bin")}:${System.getenv("PATH")}",
                            "COMMIT" to commit,
                            "REPOSITORY" to "example/project",
                            "RUNNER_TEMP" to root.resolve("runner").toString(),
                            "GITHUB_OUTPUT" to root.resolve("outputs").toString(),
                            "GITHUB_STEP_SUMMARY" to root.resolve("summary").toString(),
                            "MOCK_RUNS" to root.resolve("runs.json").toString(),
                            "MOCK_ARTIFACTS" to root.resolve("artifacts").toString(),
                            "MOCK_API_EXIT" to apiExit.toString(),
                        ),
                    )
                }.start()
        return process.waitFor()
    }
}
