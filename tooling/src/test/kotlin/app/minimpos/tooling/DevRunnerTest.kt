package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DevRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `test lane preserves pattern and flags without shell evaluation`() {
        val root = fixture()
        val result = run(root, "test", "app", "*CheckoutTest.name; echo unsafe", "--stacktrace")
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).contains("<:app:testDebugUnitTest>\n<--tests>\n<*CheckoutTest.name; echo unsafe>\n<--stacktrace>")
    }

    @Test
    fun `architecture lane checks all modules without running whole suites`() {
        val result = run(fixture(), "check", "architecture")
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).contains("<:core:test>\n<--tests>\n<*ArchitectureTest>")
        assertThat(result.second).contains("<:adyen:test>\n<--tests>\n<*ArchitectureTest>")
        assertThat(result.second).contains("<:app:testDebugUnitTest>\n<--tests>\n<*ArchitectureTest>")
    }

    @Test
    fun `finish formats then collects all findings without hiding failure`() {
        val root = fixture("case \"${'$'}*\" in *qualityGate*) exit 37 ;; esac")
        val result = run(root, "finish", "--stacktrace")
        assertThat(result.first).isEqualTo(37)
        assertThat(result.second).contains("<spotlessApply>")
        assertThat(result.second).contains("<qualityGate>\n<--continue>\n<--stacktrace>")
    }

    @Test
    fun `format failure blocks final gate`() {
        val result = run(fixture("exit 23"), "finish")
        assertThat(result.first).isEqualTo(23)
        assertThat(result.second).doesNotContain("qualityGate")
    }

    @Test
    fun `invalid lanes missing patterns and worker counts fail before Gradle`() {
        for (args in listOf(listOf("check", "unknown"), listOf("test", "app"), listOf("test", "unknown", "*"), listOf("profile", "0"))) {
            val result = run(fixture(), *args.toTypedArray())
            assertThat(result.first).isEqualTo(2)
            assertThat(result.second).doesNotContain("<")
        }
    }

    @Test
    fun `profile reruns only selected test task with explicit worker count`() {
        val result = run(fixture(), "profile", "1", "*AppFlowTest", "--stacktrace")
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).contains("<:app:testDebugUnitTest>\n<--rerun>\n<--tests>\n<*AppFlowTest>")
        assertThat(result.second).contains("<-PappTestWorkers=1>\n<--profile>\n<--stacktrace>")
    }

    @Test
    fun `help succeeds without Gradle or local configuration`() {
        val result = run(fixture(), "help")
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).contains("finish")
        assertThat(result.second).doesNotContain("<")
    }

    @Test
    fun `preflight accepts SDK minor-directory spelling paths with spaces and local SDK precedence`() {
        val root = fixture()
        val sdk = temporary.newFolder("SDK with spaces").toPath()
        Files.createDirectories(sdk.resolve("platforms/android-37.0"))
        Files.writeString(sdk.resolve("platforms/android-37.0/android.jar"), "fixture")
        val aapt = Files.createDirectories(sdk.resolve("build-tools/37.0.0")).resolve("aapt2")
        Files.writeString(aapt, "#!/bin/sh\nexit 0\n").toFile().setExecutable(true)
        val bin = Files.createDirectories(root.resolve("bin"))
        for ((name, body) in mapOf("java" to "echo 'openjdk version \"21.0.1\"' >&2", "javac" to "exit 0", "chrome" to "exit 0")) {
            Files.writeString(bin.resolve(name), "#!/bin/sh\n$body\n").toFile().setExecutable(true)
        }
        Files.writeString(root.resolve("local.properties"), "sdk.dir=${sdk.toString().replace(" ", "\\ ")}\n")
        val result =
            run(
                root,
                "preflight",
                environment =
                    mapOf(
                        "PATH" to "$bin:${System.getenv("PATH")}",
                        "JAVA_HOME" to root.toString(),
                        "ANDROID_HOME" to "/missing",
                        "MINIMPOS_CHROME" to bin.resolve("chrome").toString(),
                    ),
            )
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).contains("SDK 37: OK")
        assertThat(result.second).contains("not cached")
        assertThat(Files.exists(root.resolve("build"))).isFalse()
    }

    @Test
    fun `preflight collects missing prerequisites and never starts Gradle`() {
        val root = fixture()
        Files.writeString(root.resolve("local.properties"), "sdk.dir=/missing\n")
        val result = run(root, "preflight", environment = mapOf("MINIMPOS_CHROME" to "/missing"))
        assertThat(result.first).isEqualTo(1)
        assertThat(result.second).contains("SDK: install platform 37")
        assertThat(result.second).contains("Chrome: install")
        assertThat(result.second).doesNotContain("<")
    }

    @Test
    fun `warm refuses unsupported JAVA_HOME even when PATH has a supported JDK`() {
        val root = fixture()
        val home = temporary.newFolder("old JDK with spaces").toPath()
        val bin = Files.createDirectories(home.resolve("bin"))
        Files.writeString(bin.resolve("java"), "#!/bin/sh\necho 'openjdk version \"11.0.1\"' >&2\n").toFile().setExecutable(true)
        Files.writeString(root.resolve("local.properties"), "sdk.dir=/missing\n")
        val result = run(root, "warm", environment = mapOf("JAVA_HOME" to home.toString(), "MINIMPOS_CHROME" to "/missing"))
        assertThat(result.first).isEqualTo(1)
        assertThat(result.second).contains("JDK: need JDK 17+")
        assertThat(result.second).doesNotContain("<")
    }

    private fun fixture(body: String = ""): Path {
        val root = temporary.newFolder().toPath()
        Files.createDirectories(root.resolve("scripts"))
        Files.copy(Path.of(System.getProperty("minimpos.repository"), "scripts/dev"), root.resolve("scripts/dev"))
        Files.writeString(root.resolve("scripts/gradle"), "#!/bin/bash\nprintf '<%s>\\n' \"${'$'}@\"\n$body\n")
        return root
    }

    private fun run(
        root: Path,
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("bash", root.resolve("scripts/dev").toString()) + args)
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Dev runner timed out" }
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
