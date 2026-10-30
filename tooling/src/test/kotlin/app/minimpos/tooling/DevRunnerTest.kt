package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class DevRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `test lane preserves pattern and flags without shell evaluation`() {
        val root = fixture()
        val result = run(root, "test", "app", "*CheckoutTest.name; echo unsafe", "--stacktrace")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<:app:testDebugUnitTest>\n<--tests>\n<*CheckoutTest.name; echo unsafe>\n<--stacktrace>")
    }

    @Test
    fun `architecture lane checks all modules without running whole suites`() {
        val result = run(fixture(), "check", "architecture")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<:core:test>\n<--tests>\n<*ArchitectureTest>")
        assertThat(result.output).contains("<:adyen:test>\n<--tests>\n<*ArchitectureTest>")
        assertThat(result.output).contains("<:app:testDebugUnitTest>\n<--tests>\n<*ArchitectureTest>")
    }

    @Test
    fun `payment lane includes stored history and receipt tests after package moves`() {
        val result = run(fixture(), "check", "payment")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<--tests>\n<*payment.*>")
        assertThat(result.output).contains("<--tests>\n<*history.*>")
        assertThat(result.output).contains("<--tests>\n<*receipt.*>")
        assertThat(result.output).contains("<:app:detektDebug>")
    }

    @Test
    fun `screenshot lanes compare or record only the golden image tests`() {
        val compare = run(fixture(), "check", "ui-snapshots", "--stacktrace")
        assertThat(compare.code).isEqualTo(0)
        assertThat(compare.output).contains(
            "<:app:verifyRoborazziDebug>\n<--tests>\n<*DesignGalleryTest>\n<--tests>\n<*ScreenGoldensTest>\n<--stacktrace>",
        )
        val record = run(fixture(), "snapshots", "record", "--stacktrace")
        assertThat(record.code).isEqualTo(0)
        assertThat(record.output).contains(
            "<:app:recordRoborazziDebug>\n<--tests>\n<*DesignGalleryTest>\n<--tests>\n<*ScreenGoldensTest>\n<--stacktrace>",
        )
        val unknown = run(fixture(), "snapshots", "verify")
        assertThat(unknown.code).isEqualTo(2)
        assertThat(unknown.output).doesNotContain("<")
    }

    @Test
    fun `finish formats then collects all findings without hiding failure`() {
        val root = fixture("case \"${'$'}*\" in *qualityGate*) exit 37 ;; esac")
        val result = run(root, "finish", "--stacktrace")
        assertThat(result.code).isEqualTo(37)
        assertThat(result.output).contains("<spotlessApply>")
        assertThat(result.output).contains("<qualityGate>\n<--continue>\n<--stacktrace>")
    }

    @Test
    fun `format failure blocks final gate`() {
        val result = run(fixture("exit 23"), "finish")
        assertThat(result.code).isEqualTo(23)
        assertThat(result.output).doesNotContain("qualityGate")
    }

    @Test
    fun `invalid lanes missing patterns and worker counts fail before Gradle`() {
        for (args in listOf(listOf("check", "unknown"), listOf("test", "app"), listOf("test", "unknown", "*"), listOf("profile", "0"))) {
            val result = run(fixture(), *args.toTypedArray())
            assertThat(result.code).isEqualTo(2)
            assertThat(result.output).doesNotContain("<")
        }
    }

    @Test
    fun `profile reruns only selected test task with explicit worker count`() {
        val result = run(fixture(), "profile", "1", "*AppFlowTest", "--stacktrace")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<:app:testDebugUnitTest>\n<--rerun>\n<--tests>\n<*AppFlowTest>")
        assertThat(result.output).contains("<-PappTestWorkers=1>\n<--profile>\n<--stacktrace>")
    }

    @Test
    fun `mutation runs only the separate mutation task with trailing flags`() {
        val result = run(fixture(), "mutation", "--continue")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("<mutationTest>\n<--continue>")
        assertThat(result.output).doesNotContain("qualityGate")
    }

    @Test
    fun `help succeeds without Gradle or local configuration`() {
        val result = run(fixture(), "help")
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("finish")
        assertThat(result.output).doesNotContain("<")
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
        assertThat(result.code).isEqualTo(0)
        assertThat(result.output).contains("SDK 37: OK")
        assertThat(result.output).contains("not cached")
        assertThat(Files.exists(root.resolve("build"))).isFalse()
    }

    @Test
    fun `preflight collects missing prerequisites and never starts Gradle`() {
        val root = fixture()
        Files.writeString(root.resolve("local.properties"), "sdk.dir=/missing\n")
        val result = run(root, "preflight", environment = mapOf("MINIMPOS_CHROME" to "/missing"))
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("SDK: install platform 37")
        assertThat(result.output).contains("Chrome: install")
        assertThat(result.output).doesNotContain("<")
    }

    @Test
    fun `warm refuses unsupported JAVA_HOME even when PATH has a supported JDK`() {
        val root = fixture()
        val home = temporary.newFolder("old JDK with spaces").toPath()
        val bin = Files.createDirectories(home.resolve("bin"))
        Files.writeString(bin.resolve("java"), "#!/bin/sh\necho 'openjdk version \"11.0.1\"' >&2\n").toFile().setExecutable(true)
        Files.writeString(root.resolve("local.properties"), "sdk.dir=/missing\n")
        val result = run(root, "warm", environment = mapOf("JAVA_HOME" to home.toString(), "MINIMPOS_CHROME" to "/missing"))
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("JDK: need JDK 17+")
        assertThat(result.output).doesNotContain("<")
    }

    private fun fixture(body: String = ""): Path {
        val root = temporary.newFolder().toPath()
        Files.createDirectories(root.resolve("scripts"))
        Files.copy(Path.of(System.getProperty("minimpos.repository"), "scripts/dev"), root.resolve("scripts/dev"))
        Files.writeString(root.resolve("scripts/gradle"), "#!/usr/bin/env bash\nprintf '<%s>\\n' \"${'$'}@\"\n$body\n")
        return root
    }

    private fun run(
        root: Path,
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
    ): Cli.Result = Cli.runCommand(listOf("bash", root.resolve("scripts/dev").toString()) + args, environment = environment)
}
