package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class DocsCaptureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `Chinese Pay matches the action rather than the simulator disclosure`() {
        val root = fixture()
        val result = run(root, "tap", "zh-CN", "^checkout_pay")
        assertThat(result.code).isEqualTo(0)
        assertThat(Files.readString(root.resolve("calls"))).contains("shell input tap 360 950")
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("shell input tap 360 180")
    }

    @Test
    fun `a failed dump retries without reading a stale hierarchy`() {
        val root = fixture()
        val result = run(root, "tap", "zh-CN", "^checkout_pay", failOnce = true)
        assertThat(result.code).isEqualTo(0)
        val calls = Files.readAllLines(root.resolve("calls"))
        assertThat(calls.count { it.startsWith("shell uiautomator dump") }).isEqualTo(2)
        assertThat(calls.count { it.startsWith("exec-out cat") }).isEqualTo(1)
        assertThat(calls.last { it.startsWith("shell input tap") }).isEqualTo("shell input tap 360 950")
    }

    @Test
    fun `locale restores terminal identity and time zone after restarting the framework`() {
        val root = fixture()
        assertThat(run(root, "locale", "zh-CN").code).isEqualTo(0)
        val calls = Files.readAllLines(root.resolve("calls"))
        val restart = calls.indexOf("shell start")
        assertThat(restart).isAtLeast(0)
        assertThat(calls.indexOf("shell settings put global device_name S1F2-000158213605014")).isGreaterThan(restart)
        assertThat(calls.indexOf("shell settings put global auto_time_zone 0")).isGreaterThan(restart)
        assertThat(calls.indexOf("shell cmd alarm set-timezone Asia/Singapore")).isGreaterThan(restart)
    }

    @Test
    fun `launch waits for localized Home after a failed startup dump`() {
        val root = fixture()
        assertThat(run(root, "launch", "zh-CN", failOnce = true).code).isEqualTo(0)
        val calls = Files.readAllLines(root.resolve("calls"))
        assertThat(calls.count { it.startsWith("shell uiautomator dump") }).isEqualTo(2)
    }

    @Test
    fun `Home capture refuses splash screens rather than saving a mislabeled image`() {
        val root = fixture()
        val result = run(root, "shot", "zh-CN", "home", homeReady = false)
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("Screen never became ready")
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("exec-out screencap")
        assertThat(Files.exists(root.resolve("shots/zh-CN-home.png"))).isFalse()
    }

    @Test
    fun `demo resets require explicit opt-in a real emulator and an owned disposable clone`() {
        val root = fixture()
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar").code).isEqualTo(1)
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true, avd = "Merchant").code).isEqualTo(1)
        assertThat(Files.exists(root.resolve("calls"))).isTrue()
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("rm -rf")
        Files.writeString(root.resolve("qemu"), "0")
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true).code).isEqualTo(1)
        Files.writeString(root.resolve("qemu"), "1")
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true).code).isEqualTo(0)
        assertThat(Files.readString(root.resolve("calls"))).contains("rm -rf /data/data/app.minimpos/*")
        Files.writeString(root.resolve("calls"), "")
        Files.delete(root.resolve("avds/MiniMpos_Docs_Disposable_test.avd/minimpos-docs-disposable"))
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true).code).isEqualTo(1)
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("rm -rf")
    }

    @Test
    fun `missing archives invalid names or missing app UIDs fail before clearing data`() {
        val root = fixture()
        assertThat(run(root, "restore", "../unsafe.tar", reset = true).code).isEqualTo(2)
        Files.delete(root.resolve("snapshot-present"))
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true).code).isEqualTo(1)
        Files.writeString(root.resolve("snapshot-present"), "")
        Files.writeString(root.resolve("uid"), "not an app UID")
        assertThat(run(root, "restore", "docs-1017-zh-CN.tar", reset = true).code).isEqualTo(1)
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("rm -rf")
    }

    @Test
    fun `unavailable emulators time out without resetting or touching another device`() {
        val root = fixture()
        Files.writeString(root.resolve("device-state"), "offline")
        val result = run(root, "restore", "docs-1017-zh-CN.tar", reset = true)
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("Emulator never became available")
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("rm -rf")
    }

    @Test
    fun `install builds current debug APK and shutdown powers off only the owned emulator`() {
        val root = fixture()
        assertThat(run(root, "install", reset = true).code).isEqualTo(0)
        val calls = Files.readString(root.resolve("calls"))
        assertThat(calls).contains("gradle :app:assembleDebug")
        assertThat(calls).contains("install -r ${root.resolve("app/build/outputs/apk/debug/app-debug.apk")}")
        assertThat(run(root, "shutdown", reset = true).code).isEqualTo(0)
        assertThat(Files.readString(root.resolve("calls"))).contains("shell reboot -p")
    }

    @Test
    fun `non-simulator fixtures cannot launch a capture flow`() {
        val root = fixture()
        Files.writeString(root.resolve("settings.json"), """{"terminal":{"mode":"CLOUD"}}""")
        val result = run(root, "flow", "zh-CN", "home", reset = true)
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("Refusing non-simulator fixture")
        assertThat(Files.readString(root.resolve("calls"))).doesNotContain("am start")
    }

    private fun fixture(): Path {
        val root = temporary.newFolder().toPath()
        Files.writeString(root.resolve("snapshot-present"), "")
        Files.writeString(root.resolve("uid"), "10000")
        Files.writeString(root.resolve("qemu"), "1")
        Files.writeString(root.resolve("device-state"), "device")
        Files.writeString(root.resolve("settings.json"), """{"terminal":{"mode":"SIMULATOR"}}""")
        val clone = Files.createDirectories(root.resolve("avds/MiniMpos_Docs_Disposable_test.avd"))
        Files.writeString(clone.resolve("minimpos-docs-disposable"), "synthetic test clone")
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files
            .writeString(
                scripts.resolve("gradle"),
                "#!/bin/sh\nprintf 'gradle %s\\n' \"${'$'}*\" >> \"${'$'}FIXTURE/calls\"\n",
            ).toFile()
            .setExecutable(true)
        Files.copy(Path.of(System.getProperty("minimpos.repository"), "scripts/docs-capture"), scripts.resolve("docs-capture"))
        val resources = Files.createDirectories(root.resolve("app/src/main/res/values-zh-rCN"))
        Files.writeString(
            resources.resolve("strings.xml"),
            "<string name=\"checkout_pay\">支付 %1${'$'}s</string>\n" +
                "<string name=\"home_new_sale\">新建销售</string>\n" +
                "<string name=\"home_settings\">设置</string>\n",
        )
        val skill = Files.createDirectories(root.resolve(".devin/skills/docs-screenshots"))
        Files.writeString(skill.resolve("sample-data.json"), """{"zh-CN":{"deviceLocale":"zh-SG","timeZone":"Asia/Singapore"}}""")
        val bin = Files.createDirectories(root.resolve("bin"))
        Files.writeString(bin.resolve("sleep"), "#!/bin/sh\nexit 0\n").toFile().setExecutable(true)
        val tools = Files.createDirectories(root.resolve("platform-tools"))
        fakeAdb(tools)
        return root
    }

    private fun fakeAdb(tools: Path) {
        Files
            .writeString(
                tools.resolve("adb"),
                """#!/usr/bin/env bash
            |set -eu
            |shift 2
            |printf '%s\n' "${'$'}*" >> "${'$'}FIXTURE/calls"
            |case "${'$'}1 ${'$'}{2:-}" in
            |  'get-state ') cat "${'$'}FIXTURE/device-state" ;;
            |  'shell uiautomator')
            |    if [ "${'$'}FAIL_ONCE" = 1 ] && [ ! -f "${'$'}FIXTURE/failed" ]; then
            |      touch "${'$'}FIXTURE/failed"
            |      exit 1
            |    fi ;;
            |  'exec-out cat')
            |    printf '%s\n' '<node text="模拟器 — 不会进行真实支付" bounds="[0,160][720,200]"/>'
            |    printf '%s\n' '<node text="支付 ${'$'}33.50" bounds="[30,900][690,1000]"/>'
            |    if [ "${'$'}HOME_READY" = 1 ]; then
            |      printf '%s\n' '<node text="新建销售" bounds="[30,300][690,400]"/>'
            |      printf '%s\n' '<node text="设置" bounds="[400,1100][690,1200]"/>'
            |    fi ;;
            |  'shell getprop')
            |    if [ "${'$'}3" = ro.kernel.qemu ]; then cat "${'$'}FIXTURE/qemu"; else echo 1; fi ;;
            |  'shell cat') cat "${'$'}FIXTURE/settings.json" ;;
            |  'emu avd') echo "${'$'}AVD_NAME"; echo OK ;;
            |  'shell stat') cat "${'$'}FIXTURE/uid" ;;
            |  'shell test') test -f "${'$'}FIXTURE/snapshot-present" ;;
            |  'shell cmd')
            |    if [ "${'$'}3" = package ]; then
            |      if [ "${'$'}4" = path ]; then echo package:/system/framework/framework-res.apk
            |      else echo app.minimpos/.app.MainActivity; fi
            |    fi ;;
            |esac
                """.trimMargin(),
            ).toFile()
            .setExecutable(true)
    }

    private fun run(
        root: Path,
        vararg args: String,
        failOnce: Boolean = false,
        homeReady: Boolean = true,
        reset: Boolean = false,
        avd: String = "MiniMpos_Docs_Disposable_test",
    ): Cli.Result =
        Cli.runCommand(
            listOf("bash", root.resolve("scripts/docs-capture").toString()) + args,
            environment =
                mapOf(
                    "ANDROID_HOME" to root.toString(),
                    "ANDROID_SERIAL" to "emulator-5570",
                    "FIXTURE" to root.toString(),
                    "FAIL_ONCE" to if (failOnce) "1" else "0",
                    "HOME_READY" to if (homeReady) "1" else "0",
                    "DOCS_RESET" to if (reset) "1" else "0",
                    "ANDROID_AVD_HOME" to root.resolve("avds").toString(),
                    "AVD_NAME" to avd,
                    "SHOTS" to root.resolve("shots").toString(),
                    "PATH" to "${root.resolve("bin")}:${System.getenv("PATH")}",
                ),
        )
}
