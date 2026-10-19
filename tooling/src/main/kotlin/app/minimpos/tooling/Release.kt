package app.minimpos.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Offline version preparation and exact-CI-artifact verification; file/validation errors fail closed. */
internal object Release {
    const val APK = "app-release-unsigned.apk"
    const val MANIFEST = "release.json"
    const val TOOLING = "minimpos-tooling.jar"
    const val MAX_CODE = 2_100_000_000
    private val nameLine = Regex("^versionName=(.*)$", RegexOption.MULTILINE)
    private val codeLine = Regex("^versionCode=(.*)$", RegexOption.MULTILINE)
    private val versionName = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val positiveInteger = Regex("[1-9][0-9]*")
    private val commitSha = Regex("[0-9a-f]{40}")
    private const val HEX_RADIX = 16
    private const val HEX_BYTE_WIDTH = 2

    data class Version(
        val name: String,
        val code: Int,
    )

    fun readVersion(path: Path): Version {
        val text = Files.readString(path)
        val names = nameLine.findAll(text).map { it.groupValues[1] }.toList()
        val codes = codeLine.findAll(text).map { it.groupValues[1] }.toList()
        val name = names.singleOrNull()
        val codeText = codes.singleOrNull()
        val code = codeText?.toIntOrNull()
        require(
            name != null &&
                versionName.matches(name) &&
                codeText != null &&
                positiveInteger.matches(codeText) &&
                code != null &&
                code <= MAX_CODE,
        ) { "Invalid version.properties" }
        return Version(name, code)
    }

    fun bumpVersion(
        path: Path,
        bump: String,
    ): Version {
        val current = readVersion(path)
        val index = listOf("major", "minor", "patch").indexOf(bump)
        require(index >= 0 && current.code < MAX_CODE) { "Invalid version bump or exhausted Android versionCode" }
        val parts =
            current.name
                .split('.')
                .map(String::toBigInteger)
                .toMutableList()
        parts[index] += BigInteger.ONE
        for (position in (index + 1)..<parts.size) parts[position] = BigInteger.ZERO
        val next = Version(parts.joinToString("."), current.code + 1)
        val text =
            Files.readString(path).let { original ->
                codeLine.replaceFirst(
                    nameLine.replaceFirst(original, "versionName=${next.name}"),
                    "versionCode=${next.code}",
                )
            }
        Files.writeString(path, text)
        return next
    }

    fun isCandidate(
        subject: String,
        files: String,
        name: String,
    ): Boolean = subject == "Release $name" && files == "version.properties"

    fun selectCiRun(
        runs: JsonArray,
        commit: String,
    ): JsonObject? =
        runs
            .filterIsInstance<JsonObject>()
            .filter {
                it.string("head_sha") == commit &&
                    it.string("head_branch") == "main" &&
                    it.string("event") in setOf("push", "workflow_dispatch") &&
                    it.string("status") == "completed" &&
                    it.string("conclusion") == "success" &&
                    it.positiveInteger("id") != null &&
                    it.positiveInteger("run_attempt") != null
            }.maxByOrNull { requireNotNull(it.positiveInteger("id")) }

    fun metadata(
        version: Path,
        artifact: Path,
        commit: String,
        runId: String,
        attempt: String,
    ): JsonObject {
        require(commitSha.matches(commit)) { "Expected a full commit SHA" }
        require(positiveInteger.matches(runId) && positiveInteger.matches(attempt)) { "Expected a CI run ID and attempt" }
        val current = readVersion(version)
        return buildJsonObject {
            put("commit", commit)
            put("runId", runId)
            put("runAttempt", attempt)
            put("versionName", current.name)
            put("versionCode", current.code)
            put("apkSha256", sha256(artifact.resolve(APK)))
            put("toolingSha256", sha256(artifact.resolve(TOOLING)))
        }
    }

    fun updateMetadata(version: Path): JsonObject {
        val current = readVersion(version)
        return buildJsonObject {
            put("versionName", current.name)
            put("versionCode", current.code)
            put("apk", "minimpos-${current.name}.apk")
        }
    }

    fun checkManifest(
        version: Path,
        artifact: Path,
        commit: String,
        runId: String,
        attempt: String,
    ) {
        val actual = readJson(artifact.resolve(MANIFEST))
        val expected = metadata(version, artifact, commit, runId, attempt)
        require(actual == expected) { "Release artifact does not match the verified CI run, version or APK" }
    }

    fun readJson(path: Path): JsonElement = Json.parseToJsonElement(Files.readString(path))

    /**
     * Verifies downloaded public assets against the release tag's [version] and GitHub [metadata];
     * does not trust asset filenames alone.
     */
    fun checkPublished(
        version: Path,
        artifact: Path,
        metadata: Path,
    ) {
        val current = readVersion(version)
        val release = readJson(metadata) as? JsonObject
        require(
            release?.string("tagName") == "v${current.name}" &&
                release["isDraft"] == JsonPrimitive(false) && release["isPrerelease"] == JsonPrimitive(false),
        ) { "Expected a published stable release for this version" }
        val assets = release["assets"] as? JsonArray
        val expected = setOf("minimpos-${current.name}.apk", "update.json")
        require(assets?.size == expected.size) { "Expected exactly the APK and update.json assets" }
        val objects = assets.map { requireNotNull(it as? JsonObject) { "Invalid published asset" } }
        require(objects.map { it.string("name") }.toSet() == expected) { "Wrong or duplicate published assets" }
        objects.forEach { asset ->
            val file = artifact.resolve(requireNotNull(asset.string("name")))
            require(asset.string("digest") == "sha256:${sha256(file)}") { "Published asset digest mismatch" }
        }
        require(
            readJson(artifact.resolve("update.json")) == updateMetadata(version),
        ) { "Published update metadata differs from this version" }
    }

    fun writeJson(
        path: Path,
        value: JsonElement,
    ) {
        Files.writeString(path, "$value\n")
    }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0') }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.positiveInteger(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
}
