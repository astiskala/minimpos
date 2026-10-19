package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

class PublishedReleaseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `published assets match GitHub digests and the exact version and update contract through the CLI`() {
        val directory = fixture()
        assertThat(
            Cli
                .runJar(
                    "release",
                    "published",
                    "--version",
                    directory.resolve("version.properties").toString(),
                    "--artifact",
                    directory.toString(),
                    "--metadata",
                    directory.resolve("release.json").toString(),
                ).code,
        ).isEqualTo(0)
        assertThat(Cli.run("release", "published", "--artifact", directory.toString()).code).isEqualTo(1)
        Files.writeString(directory.resolve("minimpos-1.3.0.apk"), "modified")
        checkFails(directory, release = metadata(directory, refreshDigests = false))
    }

    @Test
    fun `wrong tag drafts prereleases missing flags and nonobjects cannot pass`() {
        val directory = fixture()
        val release = metadata(directory)
        checkFails(directory, JsonArray(emptyList()))
        for ((field, value) in listOf(
            "tagName" to JsonPrimitive("v1.3.1"),
            "isDraft" to JsonPrimitive(true),
            "isPrerelease" to JsonPrimitive(true),
            "isDraft" to JsonNull,
            "isPrerelease" to JsonNull,
        )) {
            checkFails(directory, JsonObject(release + (field to value)))
        }
    }

    @Test
    fun `asset lists require both distinct expected names with SHA-256 digests`() {
        val directory = fixture()
        val release = metadata(directory)
        val assets = release.getValue("assets") as JsonArray
        val first = assets[0] as JsonObject
        for (bad in listOf(
            JsonNull,
            JsonArray(listOf(assets[0])),
            JsonArray(assets + assets[0]),
            JsonArray(listOf(JsonNull, assets[1])),
            JsonArray(listOf(assets[0], assets[0])),
            JsonArray(listOf(JsonObject(first + ("name" to JsonNull)), assets[1])),
            JsonArray(listOf(JsonObject(first + ("digest" to JsonNull)), assets[1])),
            JsonArray(listOf(JsonObject(first + ("digest" to JsonPrimitive("sha256:bad"))), assets[1])),
        )) {
            checkFails(directory, JsonObject(release + ("assets" to bad)))
        }
    }

    @Test
    fun `even correctly hashed update metadata must match the current contract without aliases`() {
        val directory = fixture()
        Files.writeString(directory.resolve("update.json"), """{"versionName":"1.3.0","versionCode":30,"url":"minimpos-1.3.0.apk"}""")
        checkFails(directory, metadata(directory))
        Files.writeString(directory.resolve("release.json"), metadata(directory).toString())
        Files.delete(directory.resolve("minimpos-1.3.0.apk"))
        assertThat(
            Cli
                .run(
                    "release",
                    "published",
                    "--version",
                    directory.resolve("version.properties").toString(),
                    "--artifact",
                    directory.toString(),
                    "--metadata",
                    directory.resolve("release.json").toString(),
                ).code,
        ).isEqualTo(1)
    }

    private fun fixture(): Path =
        temporary.newFolder().toPath().also { directory ->
            Files.writeString(directory.resolve("version.properties"), "versionName=1.3.0\nversionCode=30\n")
            Files.writeString(directory.resolve("minimpos-1.3.0.apk"), "synthetic APK; signature checked separately by apksigner")
            Release.writeJson(directory.resolve("update.json"), Release.updateMetadata(directory.resolve("version.properties")))
            Release.writeJson(directory.resolve("release.json"), metadata(directory))
        }

    private fun metadata(
        directory: Path,
        refreshDigests: Boolean = true,
    ): JsonObject {
        if (!refreshDigests) return Release.readJson(directory.resolve("release.json")) as JsonObject
        return buildJsonObject {
            put("tagName", "v1.3.0")
            put("isDraft", false)
            put("isPrerelease", false)
            put(
                "assets",
                JsonArray(
                    listOf("minimpos-1.3.0.apk", "update.json").map { name ->
                        buildJsonObject {
                            put("name", name)
                            put(
                                "digest",
                                "sha256:" +
                                    MessageDigest
                                        .getInstance("SHA-256")
                                        .digest(Files.readAllBytes(directory.resolve(name)))
                                        .joinToString("") { "%02x".format(Locale.ROOT, it) },
                            )
                        }
                    },
                ),
            )
        }
    }

    private fun checkFails(
        directory: Path,
        release: JsonElement,
    ) {
        Release.writeJson(directory.resolve("release.json"), release)
        assertThrows(IllegalArgumentException::class.java) {
            Release.checkPublished(directory.resolve("version.properties"), directory, directory.resolve("release.json"))
        }
    }
}
