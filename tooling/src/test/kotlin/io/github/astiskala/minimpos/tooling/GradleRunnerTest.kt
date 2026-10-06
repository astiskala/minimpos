package io.github.astiskala.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GradleRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `known progress disappears but warnings and complete private logs remain`() {
        val progress =
            listOf(
                "",
                "> Task :core:compileKotlin",
                "> Task :core:test UP-TO-DATE",
                "> Task :app:testDebugUnitTest FROM-CACHE",
                "> Task :app:empty NO-SOURCE",
                "> Task :app:skip SKIPPED",
                "Reusing configuration cache.",
                "Configuration cache entry reused.",
                "Configuration cache entry stored.",
                "12 actionable tasks: 2 executed, 3 from cache, 7 up-to-date",
            )
        val retained =
            listOf(
                "BUILD SUCCESSFUL in 1s",
                "Deprecated Gradle features were used in this build.",
                "    Warning continuation with context",
                "warning: compiler diagnostic",
                "> Task :custom:output unexpected message",
                "Unrecognized tool output must survive",
            )
        val root = fixture("cat <<'OUTPUT'\n${(progress + retained).joinToString("\n")}\nOUTPUT")
        val result = run(root)
        assertThat(result.code).isEqualTo(0)
        for (line in progress.drop(1)) assertThat(result.output).doesNotContain(line)
        for (line in retained) assertThat(result.output).contains(line)
        assertThat(Files.readString(result.log)).isEqualTo("${(progress + retained).joinToString("\n")}\n")
        assertThat(Files.getPosixFilePermissions(result.log)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(Files.getPosixFilePermissions(result.log.parent)).isEqualTo(PosixFilePermissions.fromString("rwx------"))
    }

    @Test
    fun `failure keeps status failed tasks assertions and untruncated stack traces`() {
        val lines =
            listOf(
                "> Task :core:test FAILED",
                "ExampleTest > regression FAILED",
                "    java.lang.AssertionError: expected true",
                "        at ExampleTest.regression(ExampleTest.kt:42)",
            ) + List(300) { "Failure context line $it" } + "BUILD FAILED in 1s"
        val failure = lines.joinToString("\n")
        val root = fixture("cat >&2 <<'OUTPUT'\n$failure\nOUTPUT\nexit 37")
        val result = run(root)
        assertThat(result.code).isEqualTo(37)
        assertThat(result.output).contains(failure)
        assertThat(Files.readString(result.log)).isEqualTo("$failure\n")
    }

    @Test
    fun `arguments remain separate console becomes plain and concurrent logs cannot overwrite`() {
        val root = fixture("printf '<%s>\\n' \"${'$'}@\"\npwd")
        val args = listOf(":core:test", "--tests", "Example name; not a shell command", "--warning-mode=all")
        val first = CompletableFuture.supplyAsync { run(root, *(args + "--console=rich").toTypedArray()) }
        val second =
            CompletableFuture.supplyAsync {
                run(root, *(listOf("--console", "rich") + args).toTypedArray())
            }
        val results = listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
        for (result in results) {
            assertThat(result.code).isEqualTo(0)
            assertThat(result.output).contains((listOf("--console=plain") + args).joinToString("\n") { "<$it>" })
            assertThat(result.output).contains(root.toString())
        }
        assertThat(results.map { it.output.substringAfter("Full log: ").substringBefore('\n') }).containsNoDuplicates()
    }

    @Test
    fun `console task option after end-of-options marker survives`() {
        val root = fixture("printf '<%s>\\n' \"${'$'}@\"")
        val result = run(root, "--console=rich", ":example", "--", "--console=task-option")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<--console=plain>\n<:example>\n<-->\n<--console=task-option>")
    }

    @Test
    fun `missing wrapper fails with diagnostic and saved log`() {
        val result = run(fixture())
        assertThat(result.code).isNotEqualTo(0)
        assertThat(result.output).contains("Cannot run Gradle:")
        assertThat(Files.readString(result.log)).contains("Cannot run Gradle:")
    }

    @Test
    fun `INT and TERM reach wrapper and remain nonzero even when wrapper exits successfully`() {
        for ((signal, expected) in listOf("INT" to 130, "TERM" to 143)) {
            val root =
                fixture(
                    """
                    trap 'echo interrupted-INT; exit 0' INT
                    trap 'echo interrupted-TERM; exit 0' TERM
                    echo READY
                    while :; do sleep 1; done
                    """.trimIndent(),
                )
            val child = start(root)
            val ready = CountDownLatch(1)
            val text = StringBuffer()
            val reader =
                thread {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach {
                            text.append("$it\n")
                            if (it == "READY") ready.countDown()
                        }
                    }
                }
            try {
                assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue()
                assertThat(ProcessBuilder("kill", "-$signal", child.pid().toString()).start().waitFor()).isEqualTo(0)
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue()
                reader.join(TimeUnit.SECONDS.toMillis(5))
                assertThat(child.exitValue()).isEqualTo(expected)
                assertThat(text.toString()).contains("interrupted-$signal")
            } finally {
                child.descendants().forEach { it.destroyForcibly() }
                if (child.isAlive) child.destroyForcibly()
            }
        }
    }

    @Test
    fun `split UTF-8 CRLF partial lines and stderr remain intact in raw log`() {
        val root =
            fixture(
                "printf '\\344\\270'; printf '\\255\\346\\226\\207\\r\\n'; printf 'no newline'; printf 'error\\n' >&2",
            )
        val result = run(root)
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("中文\n")
        assertThat(result.output).contains("no newline")
        assertThat(result.output).contains("error\n")
        val raw = Files.readString(result.log)
        assertThat(raw).contains("中文\r\n")
        assertThat(raw).contains("no newline")
        assertThat(raw).contains("error\n")
    }

    @Test
    fun `failure packet preserves exact rerun arguments and points to full diagnostics`() {
        val root =
            fixture(
                """
                cat <<'OUTPUT'
                > Task :core:test FAILED
                    java.lang.AssertionError: expected 100 but was 90
                        at io.github.astiskala.minimpos.core.ExampleTest.check(ExampleTest.kt:42)
                        at io.github.astiskala.minimpos.core.SecondFrame.run(SecondFrame.kt:9)
                OUTPUT
                exit 37
                """.trimIndent(),
            )
        val result = run(root, ":core:test", "--tests", "*ExampleTest.shopper's amount")
        assertThat(result.code).isEqualTo(37)
        val packet = Files.readString(result.log.parent.resolve("repair.txt"))
        assertThat(packet).contains(":core:test FAILED")
        assertThat(packet).contains("expected 100 but was 90")
        assertThat(packet).contains("ExampleTest.kt:42")
        assertThat(packet).doesNotContain("SecondFrame.kt:9")
        assertThat(packet).contains(result.log.toString())
        assertThat(packet).contains("Rerun:")
        assertThat(packet).contains("scripts/dev report")
        assertThat(result.output).contains("Repair packet:")
    }

    private fun fixture(body: String? = null): Path {
        val root = temporary.newFolder().toPath()
        if (body != null) {
            val wrapper = Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n$body\n")
            Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"))
        }
        Files.createDirectories(root.resolve("scripts"))
        val launcher = root.resolve("scripts/gradle")
        Files.copy(Path.of(System.getProperty("minimpos.repository"), "scripts", "gradle"), launcher)
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))
        return root
    }

    private fun start(
        root: Path,
        vararg args: String,
    ): Process =
        ProcessBuilder(listOf(root.resolve("scripts/gradle").toString()) + args)
            .directory(temporary.root)
            .redirectErrorStream(true)
            .start()

    private fun run(
        root: Path,
        vararg args: String,
    ): Result {
        val child = start(root, *args)
        try {
            check(child.waitFor(30, TimeUnit.SECONDS)) { "Gradle runner timed out" }
            val output = child.inputStream.bufferedReader().readText()
            val log = Path.of(output.substringAfter("Full log: ").substringBefore('\n'))
            return Result(child.exitValue(), output, log)
        } finally {
            child.descendants().forEach { it.destroyForcibly() }
            if (child.isAlive) child.destroyForcibly()
        }
    }

    private data class Result(
        val code: Int,
        val output: String,
        val log: Path,
    )
}
