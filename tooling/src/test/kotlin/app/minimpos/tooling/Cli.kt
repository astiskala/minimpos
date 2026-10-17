package app.minimpos.tooling

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
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
        val process = ProcessBuilder(listOf(java, "-jar", jar) + args).redirectErrorStream(true).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Tooling CLI timed out" }
            return Result(process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
