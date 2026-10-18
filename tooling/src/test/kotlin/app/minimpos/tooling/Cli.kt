package app.minimpos.tooling

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object Cli {
    data class Result(
        val code: Int,
        val output: String,
    )

    fun run(vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val code =
            PrintStream(output, true, StandardCharsets.UTF_8).use { stream ->
                Tooling.run(args.toList(), stream, stream)
            }
        return Result(code, output.toString(StandardCharsets.UTF_8))
    }

    fun runJar(vararg args: String): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val jar = checkNotNull(System.getProperty("minimpos.tooling.jar")) { "Missing minimpos.tooling.jar" }
        return runCommand(listOf(java, "-jar", jar) + args)
    }

    fun runCommand(
        command: List<String>,
        directory: Path? = null,
        environment: Map<String, String> = emptyMap(),
    ): Result {
        // A file rather than a pipe: output is read only after exit, so a full pipe would stall the command.
        val output = Files.createTempFile("tooling-command", ".log")
        val process =
            ProcessBuilder(command)
                .directory(directory?.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .apply { environment().putAll(environment) }
                .start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Timed out: $command" }
            return Result(process.exitValue(), output.toFile().readText())
        } finally {
            stop(process)
            Files.delete(output)
        }
    }

    fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
}
