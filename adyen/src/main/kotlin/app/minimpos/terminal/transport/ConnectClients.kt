package app.minimpos.terminal.transport

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Bounded reuse of connection-timeout configurations, retaining the base client's TLS, pool and retry policy.
 * @param base Client configured once for this transport's credentials and terminal trust policy.
 */
internal class ConnectClients(
    private val base: OkHttpClient,
) {
    private val clients = LinkedHashMap<Int, OkHttpClient>(4, 0.75f, true)

    /** Client for [millis] connection milliseconds (zero means no timeout); thread-safe, at most four cached variants. */
    @Synchronized
    fun get(millis: Int): OkHttpClient =
        clients
            .getOrPut(millis) {
                base.newBuilder().connectTimeout(millis.toLong(), TimeUnit.MILLISECONDS).build()
            }.also {
                if (clients.size > MAX_CLIENTS) clients.remove(clients.keys.first())
            }

    private companion object {
        const val MAX_CLIENTS = 4
    }
}
