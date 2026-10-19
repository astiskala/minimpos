package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReleaseScriptsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `clone preserves source refuses overwrites and starts only owned read-only emulators`() {
        val root = fixture()
        val avds = Files.createDirectories(root.resolve("avds"))
        val source = Files.createDirectories(avds.resolve("Source.avd"))
        val config = "AvdId=Source\navd.ini.displayname=Source\n"
        Files.writeString(source.resolve("config.ini"), config)
        val name = "MiniMpos_Docs_Disposable_test"
        assertThat(run(root, "docs-emulator", "clone", "Source", name).code).isEqualTo(0)
        assertThat(Files.readString(source.resolve("config.ini"))).isEqualTo(config)
        assertThat(Files.readString(avds.resolve("$name.avd/config.ini"))).contains("AvdId=$name")
        assertThat(Files.readString(avds.resolve("$name.ini"))).contains("path=${avds.resolve("$name.avd")}")
        assertThat(run(root, "docs-emulator", "clone", "Source", name).code).isEqualTo(1)
        assertThat(run(root, "docs-emulator", "clone", "Source", "Unsafe").code).isEqualTo(2)
        assertThat(run(root, "docs-emulator", "clone", "Missing", "${name}2").code).isEqualTo(1)
        assertThat(run(root, "docs-emulator", "start", name, "5570").code).isEqualTo(0)
        assertThat(Files.readString(root.resolve("calls"))).contains("<-read-only>")
        assertThat(run(root, "docs-emulator", "start", name, "5571").code).isEqualTo(2)
        assertThat(run(root, "docs-emulator", "start", name, "9999").code).isEqualTo(2)
        assertThat(run(root, "docs-emulator", "start", "${name}2", "5570").code).isEqualTo(1)
    }

    @Test
    fun `asset commands preserve paths with spaces render all locales offline and propagate failures`() {
        val root = fixture()
        assertThat(run(root, "docs-assets", "process", "raw with spaces").code).isEqualTo(0)
        assertThat(Files.readString(root.resolve("calls"))).contains("<screenshots>\n<raw with spaces>")
        assertThat(run(root, "docs-assets", "review").code).isEqualTo(0)
        assertThat(run(root, "docs-assets", "social").code).isEqualTo(0)
        val calls = Files.readString(root.resolve("calls"))
        assertThat(calls).contains("<--host-resolver-rules=MAP * ~NOTFOUND>")
        for (language in listOf("en", "zh-CN", "zh-Hant", "ja")) {
            assertThat(calls).contains("/$language.html>")
        }
        assertThat(calls).contains("file://${root.resolve("build/docs-assets/social.")}")
        assertThat(calls).contains("<--user-data-dir=")
        assertThat(run(root, "docs-assets", "process", "raw", environment = mapOf("JAVA_EXIT" to "13")).code).isEqualTo(13)
        assertThat(run(root, "docs-assets", "social", environment = mapOf("CHROME_EXIT" to "17")).code).isEqualTo(17)
        assertThat(run(root, "docs-assets", "social", environment = mapOf("MINIMPOS_CHROME" to "/missing")).code).isEqualTo(1)
    }

    @Test
    fun `release verification watches only requested run checks APK and compares signer continuity without publication`() {
        val root = fixture()
        assertThat(run(root, "release-check", "watch", "123", "v1.3.0", "v1.2.1").code).isEqualTo(0)
        val calls = Files.readString(root.resolve("calls"))
        assertThat(calls).contains("<watch>\n<123>\n<--interval>\n<30>\n<--exit-status>")
        assertThat(calls).contains("<release>\n<published>")
        assertThat(calls).contains("<--min-sdk-version>\n<28>")
        assertThat(calls).contains("<-P>\n<16>")
        assertThat(calls).doesNotContain("<push>")
        assertThat(calls).doesNotContain("<create>")
        assertThat(calls).doesNotContain("<workflow>")
        assertThat(run(root, "release-check", "verify", "v1.3.0").code).isEqualTo(0)
        assertThat(
            run(root, "release-check", "verify", "v1.3.0", "v1.2.1", environment = mapOf("DIFFERENT_KEY" to "1")).code,
        ).isEqualTo(1)
        Files.writeString(root.resolve("calls"), "")
        assertThat(run(root, "release-check", "watch", "123", "v1.3.0", environment = mapOf("WATCH_EXIT" to "23")).code).isEqualTo(23)
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("<view>")
        assertThat(
            run(root, "release-check", "verify", "v1.3.0", environment = mapOf("MINIMPOS_BUILD_TOOLS" to "/missing")).code,
        ).isEqualTo(1)
    }

    @Test
    fun `help and malformed arguments never launch Gradle network or emulators`() {
        val root = fixture()
        for (script in listOf("docs-assets", "docs-emulator", "release-check")) {
            assertThat(run(root, script, "help").code).isEqualTo(0)
            assertThat(run(root, script, "unknown").code).isEqualTo(2)
        }
        assertThat(run(root, "docs-assets", "process").code).isEqualTo(2)
        assertThat(run(root, "docs-assets", "review", "unexpected").code).isEqualTo(2)
        assertThat(run(root, "release-check", "verify", "../main").code).isEqualTo(2)
        assertThat(run(root, "release-check", "verify", "v1.3.0", "--bad").code).isEqualTo(2)
        assertThat(run(root, "release-check", "watch", "bad", "v1.3.0").code).isEqualTo(2)
        assertThat(Files.exists(root.resolve("calls"))).isFalse()
    }

    private fun fixture(): Path {
        val root = temporary.newFolder("checkout " + System.nanoTime()).toPath()
        val scripts = Files.createDirectories(root.resolve("scripts"))
        for (name in listOf("docs-assets", "docs-emulator", "release-check")) {
            Files.copy(Path.of(System.getProperty("minimpos.repository"), "scripts", name), scripts.resolve(name))
        }
        executable(scripts.resolve("gradle"), """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS" """)
        Files.createDirectories(root.resolve("build"))
        val bin = Files.createDirectories(root.resolve("bin"))
        executable(bin.resolve("java"), """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS"; exit "${'$'}{JAVA_EXIT:-0}" """)
        executable(bin.resolve("chrome"), """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS"; exit "${'$'}{CHROME_EXIT:-0}" """)
        executable(
            bin.resolve("gh"),
            """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS"
            |case "${'$'}1 ${'$'}2" in
            |  'api '*) printf 'versionName=1.3.0\nversionCode=30\n' ;;
            |  'run watch') exit "${'$'}{WATCH_EXIT:-0}" ;;
            |  'release view') echo '{}' ;;
            |esac
            """.trimMargin(),
        )
        val tools = Files.createDirectories(root.resolve("sdk/build-tools/37.0.0"))
        executable(
            tools.resolve("apksigner"),
            """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS"
            |key=same
            |case "${'$'}*" in *previous*) key="${'$'}{DIFFERENT_KEY:-same}" ;; esac
            |echo "Signer #1 certificate SHA-256 digest: ${'$'}key"
            """.trimMargin(),
        )
        executable(tools.resolve("zipalign"), """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS" """)
        executable(
            tools.resolve("aapt2"),
            """echo "package: name='app.minimpos' versionCode='30' versionName='1.3.0'"
            |echo "minSdkVersion:'28'"
            """.trimMargin(),
        )
        executable(
            root.resolve("sdk/emulator/emulator"),
            """printf '<%s>\n' "${'$'}@" >> "${'$'}CALLS" """,
        )
        return root
    }

    private fun executable(
        file: Path,
        body: String,
    ) {
        Files.createDirectories(file.parent)
        Files.writeString(file, "#!/bin/bash\nset -eu\n$body\n").toFile().setExecutable(true)
    }

    private fun run(
        root: Path,
        script: String,
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
    ): Cli.Result =
        Cli.runCommand(
            listOf("bash", root.resolve("scripts/$script").toString()) + args,
            environment =
                mapOf(
                    "PATH" to "${root.resolve("bin")}:${System.getenv("PATH")}",
                    "CALLS" to root.resolve("calls").toString(),
                    "ANDROID_AVD_HOME" to root.resolve("avds").toString(),
                    "ANDROID_HOME" to root.resolve("sdk").toString(),
                    "MINIMPOS_CHROME" to root.resolve("bin/chrome").toString(),
                    "MINIMPOS_BUILD_TOOLS" to "",
                ) + environment,
        )
}
