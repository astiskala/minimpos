package app.minimpos.website

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Test-only Chromium driver using stable DevTools commands and JDK HTTP/WebSocket APIs, not Node or ChromeDriver.
 * Launches a fresh, disposable profile with debugging bound to loopback and outbound DNS blocked.
 */
internal class HelperBrowser : AutoCloseable {
    private val profile = Files.createTempDirectory("minimpos-browser-")
    private val process = launch()
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val sequence = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val socket = connect()

    fun open(path: Path) {
        val url = path.toUri().toString()
        val answer = command("Page.navigate", buildJsonObject { put("url", url) })
        check(answer["errorText"] == null) { "Cannot load local helper page" }
        await {
            executeScript(
                "return location.href === arguments[0] && document.readyState === 'complete' && " +
                    "document.getElementById('setup-form') !== null && !document.getElementById('setup-form').hidden",
                url,
            ) == true
        }
    }

    fun executeScript(
        source: String,
        vararg args: Any,
    ): Any? = evaluate("(function(){ $source }).apply(null, ${arguments(args)})")

    fun executeAsyncScript(
        source: String,
        vararg args: Any,
    ): Any? = evaluate("new Promise(done => (function(){ $source }).apply(null, [...${arguments(args)}, done]))")

    fun await(ready: () -> Boolean) = await(TIMEOUT, ready)

    private fun await(
        timeout: Duration,
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!ready()) {
            check(process.isAlive) { "Chromium exited:\n${Files.readString(profile.resolve("browser.log"))}" }
            check(
                System.nanoTime() < deadline,
            ) { "Browser condition timed out after $timeout:\n${Files.readString(profile.resolve("browser.log"))}" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
        }
    }

    override fun close() {
        socket.abort()
        stop()
    }

    private fun launch(): Process {
        val executable =
            System.getenv("MINIMPOS_CHROME")
                ?: if (System.getProperty("os.name").startsWith("Mac")) {
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                } else {
                    "google-chrome"
                }
        return ProcessBuilder(
            executable,
            "--headless=new",
            "--remote-debugging-port=0",
            "--remote-debugging-address=127.0.0.1",
            "--user-data-dir=$profile",
            "--disable-dev-shm-usage",
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-sync",
            "--no-first-run",
            "--no-default-browser-check",
            "--host-resolver-rules=MAP * ~NOTFOUND",
            "about:blank",
        ).redirectErrorStream(true)
            .redirectOutput(profile.resolve("browser.log").toFile())
            .start()
    }

    private fun connect(): WebSocket {
        var connected = false
        try {
            val portFile = profile.resolve("DevToolsActivePort")
            await(STARTUP_TIMEOUT) { Files.exists(portFile) && Files.readAllLines(portFile).size >= 2 }
            val port = Files.readAllLines(portFile).first().toInt()
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port/json/list"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "Chromium did not expose its page target" }
            val pages = Json.parseToJsonElement(response.body()) as JsonArray
            val page = pages.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
            val address = URI(requireNotNull(page["webSocketDebuggerUrl"]).jsonPrimitive.content)
            check(address.host in listOf("localhost", "127.0.0.1") && address.port == port) { "Expected loopback DevTools endpoint" }
            val result =
                http
                    .newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .buildAsync(address, Responses())
                    .get(TIMEOUT.seconds, TimeUnit.SECONDS)
            connected = true
            return result
        } finally {
            if (!connected) stop(removeProfile = false)
        }
    }

    private fun evaluate(expression: String): Any? {
        val answer =
            command(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                    put("awaitPromise", true)
                },
            )
        check(answer["exceptionDetails"] == null) { "Helper JavaScript failed: ${answer["exceptionDetails"]}" }
        return answer["result"]?.jsonObject?.get("value")?.value()
    }

    private fun command(
        method: String,
        params: JsonObject,
    ): JsonObject {
        val id = sequence.incrementAndGet()
        val reply = CompletableFuture<JsonObject>()
        pending[id] = reply
        try {
            val request =
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }
            socket.sendText(request.toString(), true).get(TIMEOUT.seconds, TimeUnit.SECONDS)
            val answer = reply.get(TIMEOUT.seconds, TimeUnit.SECONDS)
            check(answer["error"] == null) { "DevTools command failed: ${answer["error"]}" }
            return requireNotNull(answer["result"]).jsonObject
        } finally {
            pending.remove(id)
        }
    }

    private fun arguments(args: Array<out Any>): JsonArray =
        JsonArray(
            args.map {
                when (it) {
                    is String -> JsonPrimitive(it)
                    is Boolean -> JsonPrimitive(it)
                    is Int -> JsonPrimitive(it)
                    else -> throw IllegalArgumentException("Unsupported browser fixture argument")
                }
            },
        )

    private fun JsonElement.value(): Any? =
        when (this) {
            JsonNull -> null
            is JsonArray -> map { it.value() }
            is JsonObject -> this
            is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull
        }

    private fun stop(removeProfile: Boolean = true) {
        // Chromium's helper processes (crashpad, GPU, renderers) can outlive the browser and keep writing to the
        // profile, so they are stopped too before it is deleted.
        val helpers = process.descendants().toList()
        process.destroy()
        if (!process.waitFor(TIMEOUT.seconds, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        helpers.forEach { it.destroy() }
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (helpers.any { it.isAlive } && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))
        }
        helpers.filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (removeProfile) removeProfile()
    }

    /** Deletes the profile, walking it again while files that exiting processes flushed still appear. */
    private fun removeProfile() {
        repeat(PROFILE_REMOVAL_ATTEMPTS) { attempt ->
            try {
                Files.walk(profile).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                return
            } catch (e: DirectoryNotEmptyException) {
                retry(attempt, e)
            } catch (e: UncheckedIOException) {
                // A file vanished while the profile was walked.
                retry(attempt, e)
            }
        }
    }

    private fun retry(
        attempt: Int,
        error: Exception,
    ) {
        if (attempt == PROFILE_REMOVAL_ATTEMPTS - 1) throw error
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PROFILE_REMOVAL_PAUSE_MILLIS))
    }

    private inner class Responses : WebSocket.Listener {
        private val message = StringBuilder()

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            message.append(data)
            if (last) {
                val answer = Json.parseToJsonElement(message.toString()).jsonObject
                message.setLength(0)
                answer["id"]?.jsonPrimitive?.intOrNull?.let { pending[it]?.complete(answer) }
            }
            webSocket.request(1)
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            pending.values.forEach { it.completeExceptionally(error) }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val POLL_MILLIS = 10L
        const val PROFILE_REMOVAL_ATTEMPTS = 20
        const val PROFILE_REMOVAL_PAUSE_MILLIS = 100L
    }
}
