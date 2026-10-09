package app.minimpos.tooling

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object Cli {
    val java: String = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val jar: String = System.getProperty("minimpos.tooling.jar")

    data class Result(
        val code: Int,
        val output: String,
    )

    fun start(vararg args: String): Process = ProcessBuilder(listOf(java, "-jar", jar) + args).redirectErrorStream(true).start()

    fun run(vararg args: String): Result {
        val process = start(*args)
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
