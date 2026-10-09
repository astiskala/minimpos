package app.minimpos.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
                codeText.all { it in '0'..'9' } &&
                code != null &&
                code in 1..MAX_CODE,
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
        for (position in index + 1 until parts.size) parts[position] = BigInteger.ZERO
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

    fun writeJson(
        path: Path,
        value: JsonElement,
    ) {
        Files.writeString(path, "$value\n")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(bytes)
            while (count >= 0) {
                digest.update(bytes, 0, count)
                count = input.read(bytes)
            }
        }
        return digest.digest().joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0') }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.positiveInteger(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
}
