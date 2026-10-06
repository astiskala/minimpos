package io.github.astiskala.minimpos.tooling

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

/** JVM-only entry point; release errors never print input values or signing secrets. */
internal object Tooling {
    @JvmStatic
    fun main(args: Array<String>) {
        val code =
            try {
                execute(args.toList())
            } catch (exception: IllegalArgumentException) {
                System.err.println(exception.message)
                1
            } catch (exception: IOException) {
                System.err.println("Cannot access tooling input/output: ${exception.javaClass.simpleName}")
                1
            }
        exitProcess(code)
    }

    private fun execute(args: List<String>): Int {
        when (args.firstOrNull()) {
            "release" -> {
                release(Arguments(args.drop(1)))
            }

            "signing" -> {
                require(args.size == 2) { "Expected signing and a private directory" }
                SigningProperties.extract(Path.of(args[1]))
            }

            else -> {
                throw IllegalArgumentException("Expected release or signing")
            }
        }
        return 0
    }

    private fun release(args: Arguments) {
        val version = Path.of(args.optional("version") ?: "version.properties")
        when (args.positionals.firstOrNull()) {
            "bump" -> {
                Release.bumpVersion(version, args.positionals.getOrNull(1).orEmpty())
            }

            "candidate" -> {
                require(Release.isCandidate(args.required("subject"), args.required("files"), Release.readVersion(version).name)) {
                    "Resume requires an unpublished version-only release commit"
                }
            }

            "manifest", "verify" -> {
                manifest(args, version)
            }

            "ci" -> {
                val root = Release.readJson(Path.of(args.required("runs"))) as? JsonObject
                val runs = root?.get("workflow_runs") as? JsonArray
                val selected =
                    Release.selectCiRun(
                        requireNotNull(runs) { "Expected workflow_runs array" },
                        args.required("commit"),
                        args.required("not-before"),
                    )
                System.out.println(selected ?: JsonNull)
            }

            "update" -> {
                Release.writeJson(Path.of(args.optional("out") ?: "update.json"), Release.updateMetadata(version))
            }

            else -> {
                throw IllegalArgumentException("Expected bump, candidate, manifest, verify, ci or update")
            }
        }
    }

    private fun manifest(
        args: Arguments,
        version: Path,
    ) {
        val artifact = Path.of(args.required("artifact"))
        val commit = args.required("commit")
        val runId = args.required("run-id")
        val attempt = args.required("run-attempt")
        if (args.positionals[0] == "manifest") {
            Release.writeJson(artifact.resolve(Release.MANIFEST), Release.metadata(version, artifact, commit, runId, attempt))
        } else {
            Release.checkManifest(version, artifact, commit, runId, attempt)
        }
    }
}

/** Strict string options matching the release CLI; positional commands remain independent of option order. */
private class Arguments(
    args: List<String>,
) {
    val positionals = mutableListOf<String>()
    private val values = mutableMapOf<String, String>()

    init {
        val supported =
            setOf("version", "artifact", "commit", "run-id", "run-attempt", "subject", "files", "runs", "not-before", "out")
        val iterator = args.iterator()
        while (iterator.hasNext()) {
            val value = iterator.next()
            if (value.startsWith("--")) {
                val key = value.removePrefix("--")
                require(key in supported && iterator.hasNext() && key !in values) { "Invalid or duplicate release option" }
                values[key] = iterator.next()
            } else {
                require(!value.startsWith("-")) { "Invalid release option" }
                positionals.add(value)
            }
        }
        require(positionals.size in 1..2) { "Expected a release command and optional version component" }
    }

    fun optional(key: String): String? = values[key]

    fun required(key: String): String = requireNotNull(values[key]) { "Missing release option: $key" }
}
