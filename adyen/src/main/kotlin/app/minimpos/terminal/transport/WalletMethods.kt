package app.minimpos.terminal.transport

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
 * @property allowed Explicit Adyen permission when supplied, otherwise the configuration's enablement.
 * @property channel Explicit sales channel, or pos for an unambiguous `_pos` variant when absent; empty otherwise.
 * @property currencies Configured currencies; empty or containing `ANY` means no additional currency restriction.
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
     * All configuration pages, including inactive methods for the decision owner to filter. An empty list is a
     * successful lookup with no matching configurations at the requested scope, not a discovery failure.
     * @property methods Configurations in response order; no secrets or raw JSON.
     */
    data class Listed(
        val methods: List<WalletMethod>,
    ) : WalletMethodListing

    /**
     * Lookup failed; [Fault.Credential] and [Fault.Permission] must invalidate a previously verified list.
     * @property fault Why; an unreadable answer is not an empty configuration.
     */
    data class Failed(
        val fault: Fault,
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
 * Requires [AdyenRole.PAYMENT_METHODS_READ]. Pagination stays at the supplied endpoint; links are never followed.
 * Real enabled configurations may omit `allowed` and `shopperInteraction`. Enablement supplies the former only when
 * absent; only an explicit `_pos` variant supplies a missing channel. Explicit denial or a different channel always wins.
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
    ): WalletMethodListing =
        when (
            val pages =
                collectManagementPages(
                    role = AdyenRole.PAYMENT_METHODS_READ.displayName,
                    fetch = { page(merchantAccount, storeId, it) },
                    parse = ::parsePage,
                )
        ) {
            is ManagementPages.Failed -> WalletMethodListing.Failed(pages.fault)
            is ManagementPages.Listed -> WalletMethodListing.Listed(pages.items)
        }

    private suspend fun page(
        merchantAccount: String,
        storeId: String,
        number: Int,
    ): AdyenReply {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchantAccount)
                .addPathSegment("paymentMethodSettings")
                .addQueryParameter("pageNumber", number.toString())
                .addQueryParameter("pageSize", MANAGEMENT_PAGE_SIZE.toString())
                .apply { if (storeId.isNotBlank()) addQueryParameter("storeId", storeId) }
                .build()
        return http.get(url, TIMEOUT)
    }

    private fun parsePage(body: String): Pair<List<WalletMethod>, Boolean>? {
        val response = decodeAdyenModel(body, PaymentMethodResponse::class.java)
        val data = response?.data
        if (data == null || !response.typesWithErrors.isNullOrEmpty() || data.any { it.type.isNullOrBlank() }) {
            return null
        }
        return data.map(::method) to
            !response.links
                ?.next
                ?.href
                .isNullOrBlank()
    }

    private fun method(value: PaymentMethod): WalletMethod =
        WalletMethod(
            type = checkNotNull(value.type),
            enabled = value.enabled == true,
            allowed = value.allowed ?: (value.enabled == true),
            channel = value.shopperInteraction ?: "pos".takeIf { value.type.endsWith("_pos") }.orEmpty(),
            currencies = value.currencies.orEmpty().toSet(),
            countries = value.countries.orEmpty().toSet(),
            storeIds = value.storeIds.orEmpty().toSet(),
        )

    private companion object {
        private val TIMEOUT = 15.seconds
    }
}
