package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.management.PaymentMethod
import com.adyen.model.management.PaymentMethodResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * Non-secret payment-method configuration, not proof that a particular scanned code can be charged.
 * @property type Exact configured Adyen payment-method type.
 * @property enabled Whether the configuration is enabled.
 * @property allowed Whether Adyen allows receiving payments.
 * @property channel Sales channel, such as pos; empty when absent and never eligible for POS by inference.
 * @property currencies Configured currencies; empty means no additional currency restriction was supplied.
 * @property countries Configured countries; empty means no additional country restriction was supplied.
 * @property storeIds Explicit store restrictions; empty means merchant-wide configuration.
 */
data class WalletMethod(
    val type: String,
    val enabled: Boolean,
    val allowed: Boolean,
    val channel: String,
    val currencies: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val storeIds: Set<String> = emptySet(),
)

/** Complete read-only lookup, never a partial list after a failed page. */
sealed interface WalletMethodListing {
    /**
     * All configuration pages, including inactive methods for the decision owner to filter.
     * @property methods Configurations in response order; no secrets or raw JSON.
     */
    data class Listed(
        val methods: List<WalletMethod>,
    ) : WalletMethodListing

    /**
     * Lookup failed; authentication and permission failures must invalidate a previously verified list.
     * @property reason Non-secret typed failure; unavailable is transient, unreadable is not an empty configuration.
     */
    data class Failed(
        val reason: ManagementFailure,
    ) : WalletMethodListing
}

/** Read-only Management configuration access; suspend calls are main-safe and never automatically retried. */
fun interface WalletMethodsApi {
    /** Reads [merchantAccount]'s current configuration, scoped to [storeId] when nonblank. */
    suspend fun methods(
        merchantAccount: String,
        storeId: String,
    ): WalletMethodListing
}

/**
 * Management v3 payment-method reads through the shared HTTP boundary and SDK wire models.
 * Requires Management API—Payment methods read. Pagination stays at the supplied endpoint; links are never followed.
 * @param apiKey Credential sent only as an authentication header.
 * @param environment Exact environment, never probed across TEST/LIVE.
 * @param baseUrl Endpoint override for offline tests.
 * @param baseClient Shared connection pool to derive from.
 * @param dispatcher Runs interruptible blocking requests.
 */
class AdyenWalletMethods(
    apiKey: String,
    environment: TerminalEnvironment,
    private val baseUrl: HttpUrl = AdyenStoreDetails.endpoint(environment).toHttpUrl(),
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WalletMethodsApi {
    private val http = AdyenHttp(apiKey, baseClient, dispatcher)

    override suspend fun methods(
        merchantAccount: String,
        storeId: String,
    ): WalletMethodListing {
        val methods = mutableListOf<WalletMethod>()
        var number = 1
        var next = true
        var problem: ManagementFailure? = null
        while (next && number <= MAX_PAGES && problem == null) {
            when (val page = page(merchantAccount, storeId, number++)) {
                is Page.Failed -> {
                    problem = page.reason
                }

                is Page.Listed -> {
                    methods += page.methods
                    next = page.next
                }
            }
        }
        return when {
            problem != null -> WalletMethodListing.Failed(problem)
            next -> WalletMethodListing.Failed(ManagementFailure.UNREADABLE)
            else -> WalletMethodListing.Listed(methods)
        }
    }

    private suspend fun page(
        merchantAccount: String,
        storeId: String,
        number: Int,
    ): Page {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchantAccount)
                .addPathSegment("paymentMethodSettings")
                .addQueryParameter("pageNumber", number.toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .apply { if (storeId.isNotBlank()) addQueryParameter("storeId", storeId) }
                .build()
        val reply = http.get(url, TIMEOUT)
        if (reply !is AdyenReply.Answered) return Page.Failed(ManagementFailure.UNAVAILABLE)
        if (!reply.ok) return Page.Failed(failure(reply.code))
        val response = decodeAdyenModel(reply.body, PaymentMethodResponse::class.java)
        val data = response?.data
        if (data == null || !response.typesWithErrors.isNullOrEmpty() || data.any { it?.type.isNullOrBlank() }) {
            return Page.Failed(ManagementFailure.UNREADABLE)
        }
        return Page.Listed(
            data.map(::method),
            !response.links
                ?.next
                ?.href
                .isNullOrBlank(),
        )
    }

    private sealed interface Page {
        data class Listed(
            val methods: List<WalletMethod>,
            val next: Boolean,
        ) : Page

        data class Failed(
            val reason: ManagementFailure,
        ) : Page
    }

    private fun method(value: PaymentMethod): WalletMethod =
        WalletMethod(
            type = checkNotNull(value.type),
            enabled = value.enabled == true,
            allowed = value.allowed == true,
            channel = value.shopperInteraction.orEmpty(),
            currencies = value.currencies.orEmpty().toSet(),
            countries = value.countries.orEmpty().toSet(),
            storeIds = value.storeIds.orEmpty().toSet(),
        )

    private fun failure(code: Int): ManagementFailure =
        when (code) {
            HTTP_UNAUTHORIZED -> ManagementFailure.AUTHENTICATION
            HTTP_FORBIDDEN -> ManagementFailure.PERMISSION
            else -> ManagementFailure.UNAVAILABLE
        }

    private companion object {
        const val MAX_PAGES = 100
        const val PAGE_SIZE = 100
        val TIMEOUT = 15.seconds
    }
}
