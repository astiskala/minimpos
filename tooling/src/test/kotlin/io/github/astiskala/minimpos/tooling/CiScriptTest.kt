package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CiScriptTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `CI runs the complete gate before the release build with the lint exemption`() {
        val fixture = fixture()
        assertThat(run(fixture)).isEqualTo(0)
        assertThat(Files.readAllLines(fixture.resolve("calls")))
            .containsExactly("qualityGate --continue", ":app:assembleRelease -PqualityGatePassed=true")
            .inOrder()
    }

    @Test
    fun `a failed gate never builds a release and keeps its exit status`() {
        val fixture = fixture()
        assertThat(run(fixture, gateExit = 7)).isEqualTo(7)
        assertThat(Files.readAllLines(fixture.resolve("calls"))).containsExactly("qualityGate --continue")
    }

    @Test
    fun `a failed release build keeps its exit status`() {
        val fixture = fixture()
        assertThat(run(fixture, releaseExit = 9)).isEqualTo(9)
        assertThat(Files.readAllLines(fixture.resolve("calls"))).hasSize(2)
    }

    @Test
    fun `local use is rejected before the gate and release lint exemption`() {
        val fixture = fixture()
        assertThat(run(fixture, ci = "false")).isEqualTo(2)
        assertThat(Files.exists(fixture.resolve("calls"))).isFalse()
    }

    private fun fixture(): Path {
        val root = temporary.newFolder("checkout with spaces").toPath()
        val scripts = Files.createDirectory(root.resolve("scripts"))
        Files.copy(Path.of(System.getProperty("minimpos.repository")).resolve("scripts/ci"), scripts.resolve("ci"))
        Files.writeString(
            root.resolve("gradlew"),
            """#!/bin/bash
            |printf '%s\n' "${'$'}*" >> "${'$'}CALLS"
            |case "${'$'}1" in
            |  qualityGate) exit "${'$'}GATE_EXIT" ;;
            |  :app:assembleRelease) exit "${'$'}RELEASE_EXIT" ;;
            |  *) exit 99 ;;
            |esac
            """.trimMargin(),
        )
        check(root.resolve("gradlew").toFile().setExecutable(true))
        return root
    }

    private fun run(
        root: Path,
        gateExit: Int = 0,
        releaseExit: Int = 0,
        ci: String = "true",
    ): Int {
        val process =
            ProcessBuilder("bash", root.resolve("scripts/ci").toString())
                .apply {
                    directory(temporary.root)
                    redirectErrorStream(true)
                    redirectOutput(root.resolve("output").toFile())
                    environment().putAll(
                        mapOf(
                            "CI" to ci,
                            "CALLS" to root.resolve("calls").toString(),
                            "GATE_EXIT" to gateExit.toString(),
                            "RELEASE_EXIT" to releaseExit.toString(),
                        ),
                    )
                }.start()
        return process.waitFor()
    }
}
