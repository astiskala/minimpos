package app.minimpos.app.terminal

import app.minimpos.terminal.paymentsapp.AppLinkExchange
import app.minimpos.terminal.transport.TerminalUnreachableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The Android side of the Adyen Payments app's App Links, between the transport that waits for an answer and the
 * activity that starts the Payments app. The activity opens every [launches] link (and reports [opened] or
 * [failed]), hands over each URL it is started with ([deliver]), and says when the operator came back without an answer
 * ([abandon]). One exchange runs at a time; the functions are safe to call from any thread.
 */
class PaymentsAppBridge : AppLinkExchange {
    /**
     * A link the activity should open.
     *
     * @property id Identifies the exchange, for [opened], [failed] and [abandon].
     * @property link The App Link.
     * @property packageName The Payments app to open it in.
     */
    data class Launch(
        val id: Long,
        val link: String,
        val packageName: String,
    )

    private val mutex = Mutex()
    private val _launches = MutableStateFlow<Launch?>(null)

    /** The link to open now, until the activity reports it [opened]; null while there is none. */
    val launches: StateFlow<Launch?> = _launches.asStateFlow()

    @Volatile private var pending: Pending? = null
    private var nextId = 0L
    private val late = CopyOnWriteArrayList<String>()

    private class Pending(
        val id: Long,
        val reply: CompletableDeferred<String> = CompletableDeferred(),
    ) {
        @Volatile var opened = false
    }

    /** The exchange waiting for the Payments app after its link was opened, which [abandon] can end; null if none. */
    val awaitingAnswer: Long? get() = pending?.takeIf { it.opened }?.id

    override suspend fun exchange(
        link: String,
        packageName: String,
        timeout: Duration,
    ): String =
        mutex.withLock {
            val waiting = Pending(++nextId)
            pending = waiting
            _launches.value = Launch(waiting.id, link, packageName)
            try {
                withTimeoutOrNull(timeout) { waiting.reply.await() } ?: throw IOException("The Adyen Payments app did not answer in time")
            } finally {
                pending = null
                _launches.value = null
            }
        }

    override fun lateReplies(): List<String> = late.toList()

    /** The activity started the Payments app with [id]'s link. */
    fun opened(id: Long) {
        pending?.takeIf { it.id == id }?.opened = true
        _launches.value = _launches.value?.takeIf { it.id != id }
    }

    /** The Payments app could not be started for [id] (it is not installed), so nothing was sent; [reason] says so. */
    fun failed(
        id: Long,
        reason: String,
    ) {
        pending?.takeIf { it.id == id }?.reply?.completeExceptionally(TerminalUnreachableException(reason))
    }

    /**
     * The activity was started with [url]. A return URL of the Payments app answers the waiting exchange, or is kept in
     * [lateReplies] when none waits (the app was restarted meanwhile). Returns whether [url] was a return URL.
     */
    fun deliver(url: String): Boolean {
        if (!url.startsWith("$RETURN_URL/")) return false
        if (pending?.reply?.complete(url) != true) late += url
        return true
    }

    /**
     * The operator came back from the Payments app without an answer to [id]; if it is still waiting, its outcome is
     * unknown from now on. The activity calls this a moment after it is resumed, so an answer that comes with the resume
     * is taken first.
     */
    fun abandon(id: Long) {
        pending
            ?.takeIf { it.id == id && it.opened }
            ?.reply
            ?.completeExceptionally(IOException("Came back from the Adyen Payments app without an answer"))
    }

    /** The return URL. */
    companion object {
        /** Where the Payments app calls back; the manifest routes it to the activity. */
        const val RETURN_URL = "minimpos://paymentsapp"
    }
}
