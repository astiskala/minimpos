package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ReleaseTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val commit = "a".repeat(40)

    @Test
    fun `patch minor and major raise both values and preserve text`() {
        val version = temporary.root.toPath().resolve("version.properties")
        for ((bump, name) in listOf("patch" to "0.6.3", "minor" to "0.7.0", "major" to "1.0.0")) {
            Files.writeString(version, "# retained\nversionName=0.6.2\nversionCode=14\n")
            assertThat(Release.bumpVersion(version, bump)).isEqualTo(Release.Version(name, 15))
            assertThat(Release.readVersion(version)).isEqualTo(Release.Version(name, 15))
            assertThat(Files.readString(version)).isEqualTo("# retained\nversionName=$name\nversionCode=15\n")
        }
    }

    @Test
    fun `semantic components do not overflow or lose precision`() {
        val version = temporary.root.toPath().resolve("version.properties")
        Files.writeString(version, "versionName=999999999999999999999999.2.3\nversionCode=14\n")
        assertThat(Release.bumpVersion(version, "major").name).isEqualTo("1000000000000000000000000.0.0")
    }

    @Test
    fun `invalid duplicate or exhausted versions remain unchanged`() {
        val version = temporary.root.toPath().resolve("version.properties")
        val texts =
            listOf(
                "versionName=0.6\nversionCode=14\n",
                "versionName=0.6.2\nversionCode=0\n",
                "versionName=0.6.2\nversionName=0.6.3\nversionCode=14\n",
                "versionName=0.6.2\nversionName=invalid\nversionCode=14\n",
                "versionName=0.6.2\nversionCode=14\nversionCode=15\n",
                "versionName=0.6.2\nversionCode=${Release.MAX_CODE}\n",
                "versionName=0.6.2\nversionCode=99999999999999999999999\n",
                "versionName=0.6.2\nversionCode=+14\n",
            )
        for (text in texts) {
            Files.writeString(version, text)
            assertThrows(IllegalArgumentException::class.java) { Release.bumpVersion(version, "patch") }
            assertThat(Files.readString(version)).isEqualTo(text)
        }
        Files.writeString(version, "versionName=0.6.2\nversionCode=14\n")
        assertThrows(IllegalArgumentException::class.java) { Release.bumpVersion(version, "unknown") }
    }

    @Test
    fun `resume accepts only version-only release commit`() {
        assertThat(Release.isCandidate("Release 0.6.2", "version.properties", "0.6.2")).isTrue()
        val invalid =
            listOf(
                "Release 0.6.1" to "version.properties",
                "Other change" to "version.properties",
                "Release 0.6.2" to "version.properties\napp/build.gradle.kts",
                "Release 0.6.2" to "",
            )
        for ((subject, files) in invalid) assertThat(Release.isCandidate(subject, files, "0.6.2")).isFalse()
    }

    @Test
    fun `CI reuse accepts exact successful main push and dispatch runs regardless of age`() {
        val run =
            json(
                """{"id":123,"run_attempt":2,"head_sha":"$commit","head_branch":"main","event":"push",
                |"created_at":"2026-10-05T00:00:00Z","status":"completed","conclusion":"success"}
                """.trimMargin(),
            )
        assertThat(Release.selectCiRun(JsonArray(listOf(run)), commit)).isEqualTo(run)
        val dispatch = JsonObject(run + ("event" to JsonPrimitive("workflow_dispatch")) + ("id" to JsonPrimitive(124)))
        assertThat(Release.selectCiRun(JsonArray(listOf(dispatch, run)), commit)).isEqualTo(dispatch)
        assertThat(Release.selectCiRun(JsonArray(listOf(run, dispatch)), commit)).isEqualTo(dispatch)
        assertThat(Release.selectCiRun(JsonArray(emptyList()), commit)).isNull()
    }

    @Test
    fun `CI reuse rejects other commits branches events incomplete failures and malformed provenance`() {
        val run =
            json(
                """{"id":123,"run_attempt":2,"head_sha":"$commit","head_branch":"main","event":"push",
                |"status":"completed","conclusion":"success"}
                """.trimMargin(),
            )
        val changes =
            mapOf(
                "head_sha" to "b".repeat(40),
                "head_branch" to "feature",
                "event" to "pull_request",
                "status" to "queued",
                "conclusion" to "failure",
            )
        for ((key, value) in changes) {
            assertThat(Release.selectCiRun(JsonArray(listOf(JsonObject(run + (key to JsonPrimitive(value))))), commit))
                .isNull()
        }
        for (key in listOf("id", "run_attempt")) {
            assertThat(Release.selectCiRun(JsonArray(listOf(JsonObject(run - key))), commit)).isNull()
            for (value in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("123"), JsonPrimitive(true))) {
                assertThat(Release.selectCiRun(JsonArray(listOf(JsonObject(run + (key to value)))), commit)).isNull()
            }
        }
        for (status in listOf("in_progress", "waiting", "requested")) {
            assertThat(Release.selectCiRun(JsonArray(listOf(JsonObject(run + ("status" to JsonPrimitive(status))))), commit)).isNull()
        }
        for (conclusion in listOf("cancelled", "skipped", "timed_out", "neutral")) {
            assertThat(
                Release.selectCiRun(JsonArray(listOf(JsonObject(run + ("conclusion" to JsonPrimitive(conclusion))))), commit),
            ).isNull()
        }
    }

    @Test
    fun `matching manifest accepts any key order but binds independent APK hash`() {
        val fixture = fixture()
        val metadata = metadata(fixture)
        assertThat(metadata["apkSha256"]).isEqualTo(JsonPrimitive("fcdfe49bb3e65c621393969c64bfb4705c12be881418fded5a0cf795a03f99c3"))
        Release.writeJson(fixture.artifact.resolve(Release.MANIFEST), JsonObject(metadata.entries.reversed().associate { it.toPair() }))
        verify(fixture)
    }

    @Test
    fun `changed APK tooling commit run attempt or version fails closed`() {
        val fixture = fixture()
        save(fixture)
        for ((sha, run, attempt) in listOf(Triple("b".repeat(40), "123", "1"), Triple(commit, "124", "1"), Triple(commit, "123", "2"))) {
            assertThrows(IllegalArgumentException::class.java) {
                Release.checkManifest(fixture.version, fixture.artifact, sha, run, attempt)
            }
        }
        Release.bumpVersion(fixture.version, "patch")
        assertThrows(IllegalArgumentException::class.java) { verify(fixture) }
        save(fixture)
        Files.writeString(fixture.artifact.resolve(Release.TOOLING), "tampered tooling")
        assertThrows(IllegalArgumentException::class.java) { verify(fixture) }
        save(fixture)
        Files.writeString(fixture.artifact.resolve(Release.APK), "tampered")
        assertThrows(IllegalArgumentException::class.java) { verify(fixture) }
    }

    @Test
    fun `missing malformed extra or wrong-typed manifest data fails closed`() {
        val fixture = fixture()
        assertThrows(IOException::class.java) { verify(fixture) }
        val values =
            listOf(
                "{",
                "null",
                "[]",
                JsonObject(metadata(fixture) + ("extra" to JsonPrimitive(true))).toString(),
                JsonObject(metadata(fixture) + ("versionCode" to JsonPrimitive("14"))).toString(),
            )
        for (value in values) {
            Files.writeString(fixture.artifact.resolve(Release.MANIFEST), value)
            assertThrows(IllegalArgumentException::class.java) { verify(fixture) }
        }
        save(fixture)
        Files.delete(fixture.artifact.resolve(Release.APK))
        assertThrows(IOException::class.java) { verify(fixture) }
    }

    @Test
    fun `invalid provenance is rejected`() {
        val fixture = fixture()
        for ((sha, run, attempt) in listOf(Triple("main", "123", "1"), Triple(commit, "0", "1"), Triple(commit, "123", "x"))) {
            assertThrows(IllegalArgumentException::class.java) { Release.metadata(fixture.version, fixture.artifact, sha, run, attempt) }
        }
    }

    @Test
    fun `update metadata retains current app format`() {
        val fixture = fixture()
        assertThat(Release.updateMetadata(fixture.version))
            .isEqualTo(json("""{"versionName":"0.6.2","versionCode":14,"apk":"minimpos-0.6.2.apk"}"""))
    }

    @Test
    fun `standalone CLI prepares versions writes and verifies manifests`() {
        val fixture = fixture()

        fun run(vararg args: String) = Cli.run("release", "--version", fixture.version.toString(), *args)
        assertThat(run("bump", "patch").code).isEqualTo(0)
        assertThat(Release.readVersion(fixture.version)).isEqualTo(Release.Version("0.6.3", 15))
        val args = arrayOf("--artifact", fixture.artifact.toString(), "--commit", commit, "--run-id", "123", "--run-attempt", "1")
        assertThat(run("manifest", *args).code).isEqualTo(0)
        assertThat(run("verify", *args).code).isEqualTo(0)
        assertThat(run("candidate", "--subject", "Release 0.6.3", "--files", "version.properties").code).isEqualTo(0)
        assertThat(run("candidate", "--subject", "Other", "--files", "version.properties").code).isNotEqualTo(0)
        val update = fixture.root.resolve("update.json")
        assertThat(run("update", "--out", update.toString()).code).isEqualTo(0)
        assertThat(Files.readString(update))
            .isEqualTo("{\"versionName\":\"0.6.3\",\"versionCode\":15,\"apk\":\"minimpos-0.6.3.apk\"}\n")
        assertThat(run("update", "--unknown", "x").code).isNotEqualTo(0)
        assertThat(run("update", "--out", "x", "--out", "y").code).isNotEqualTo(0)
    }

    @Test
    fun `standalone CLI selects successful exact CI or prints null`() {
        val fixture = fixture()
        val runs = fixture.root.resolve("runs.json")
        Files.writeString(
            runs,
            """{"workflow_runs":[{"id":123,"run_attempt":1,"head_sha":"$commit","head_branch":"main","event":"push",
            |"created_at":"2026-10-05T00:00:00Z","status":"completed","conclusion":"success"}]}
            """.trimMargin(),
        )
        val selected =
            Cli.run("release", "ci", "--runs", runs.toString(), "--commit", commit)
        assertThat(selected.code).isEqualTo(0)
        assertThat(selected.output).contains("\"status\":\"completed\"")
        val none =
            Cli.run("release", "ci", "--runs", runs.toString(), "--commit", "b".repeat(40))
        assertThat(none.code).isEqualTo(0)
        assertThat(none.output).isEqualTo("null\n")
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun fixture(): Fixture {
        val root = temporary.newFolder().toPath()
        val version = root.resolve("version.properties")
        val artifact = Files.createDirectory(root.resolve("artifact"))
        Files.writeString(version, "# retained\nversionName=0.6.2\nversionCode=14\n")
        Files.writeString(artifact.resolve(Release.APK), "unsigned APK fixture")
        Files.writeString(artifact.resolve(Release.TOOLING), "tooling JAR fixture")
        return Fixture(root, version, artifact)
    }

    private fun metadata(fixture: Fixture) = Release.metadata(fixture.version, fixture.artifact, commit, "123", "1")

    private fun save(fixture: Fixture) = Release.writeJson(fixture.artifact.resolve(Release.MANIFEST), metadata(fixture))

    private fun verify(fixture: Fixture) = Release.checkManifest(fixture.version, fixture.artifact, commit, "123", "1")

    private data class Fixture(
        val root: Path,
        val version: Path,
        val artifact: Path,
    )
}
