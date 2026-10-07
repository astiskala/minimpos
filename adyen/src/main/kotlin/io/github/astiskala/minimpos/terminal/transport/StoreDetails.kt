package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.management.DefaultErrorResponseEntity
import com.adyen.model.management.ListStoresResponse
import com.adyen.model.management.Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * Receipt fields supplied by an Adyen store; blank fields are unavailable, not instructions to erase saved text.
 *
 * @property id Adyen's store ID, not its reference.
 * @property reference Merchant's store reference; blank if absent.
 * @property name Store shopper statement, the name Adyen prints on shopper receipts; blank if absent.
 * @property address Address components in receipt order, separated by newlines; blank if absent.
 * @property phone Store phone number, normally E.164; blank if absent.
 */
data class StoreDetails(
    val id: String,
    val reference: String,
    val name: String,
    val address: String,
    val phone: String,
)

/** Result of reading store receipt details; HTTP, network and malformed-answer failures are returned, not thrown. */
sealed interface StoreListing {
    /**
     * All stores from the requested merchant account, including an empty list when none exist.
     * @property stores Receipt details in API order.
     */
    data class Listed(
        val stores: List<StoreDetails>,
    ) : StoreListing

    /**
     * The store list could not be read completely; no partial list is offered.
     * @property message Non-secret failure explanation.
     */
    data class Failed(
        val message: String,
    ) : StoreListing
}

/** Read-only Management API store access; safe to call from any thread. */
fun interface StoreDetailsApi {
    /** Reads all pages of stores belonging to [merchantAccount], using a credential with Management API—Stores read. */
    suspend fun stores(merchantAccount: String): StoreListing
}

/**
 * Reads receipt business details through Management v3, without changing Adyen configuration.
 *
 * @param apiKey The stored Adyen API key, never included in response models.
 * @param environment Detected payment environment; no environment discovery is performed here.
 * @param baseUrl Management endpoint; tests use a local server.
 * @param baseClient Shared HTTP client to derive from.
 * @param dispatcher Where interruptible network calls run.
 */
class AdyenStoreDetails(
    apiKey: String,
    environment: TerminalEnvironment,
    private val baseUrl: HttpUrl = endpoint(environment).toHttpUrl(),
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StoreDetailsApi {
    private val http = AdyenHttp(apiKey, baseClient, dispatcher)

    override suspend fun stores(merchantAccount: String): StoreListing {
        val stores = mutableListOf<StoreDetails>()
        var next = true
        var page = 1
        var failure: String? = null
        while (next && page <= MAX_PAGES && failure == null) {
            when (val result = page(merchantAccount, page++)) {
                is Page.Listed -> {
                    stores += result.stores
                    next = result.next
                }

                is Page.Failed -> {
                    failure = result.message
                }
            }
        }
        return when {
            failure != null -> StoreListing.Failed(failure)
            next -> StoreListing.Failed("The store list is too large to import")
            else -> StoreListing.Listed(stores.distinctBy { it.id })
        }
    }

    private suspend fun page(
        merchant: String,
        number: Int,
    ): Page {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchant)
                .addPathSegment("stores")
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .addQueryParameter("pageNumber", number.toString())
                .build()
        return when (val reply = http.get(url, TIMEOUT)) {
            is AdyenReply.Failed -> {
                Page.Failed(reply.message)
            }

            is AdyenReply.Answered -> {
                if (!reply.ok) Page.Failed(error(reply.code, reply.body)) else parsePage(reply.body)
            }
        }
    }

    private fun parsePage(text: String): Page =
        runCatching {
            val response = requireNotNull(decodeAdyenModel(text, ListStoresResponse::class.java))
            val stores = requireNotNull(response.data).map(::store)
            Page.Listed(
                stores,
                !response.links
                    ?.next
                    ?.href
                    .isNullOrBlank(),
            )
        }.getOrElse { Page.Failed("Adyen sent an unreadable store list") }

    private sealed interface Page {
        data class Listed(
            val stores: List<StoreDetails>,
            val next: Boolean,
        ) : Page

        data class Failed(
            val message: String,
        ) : Page
    }

    private fun store(store: Store): StoreDetails {
        val id =
            store.id
                ?.trim()
                .orEmpty()
                .also { require(it.isNotBlank()) }
        val address = store.address
        return StoreDetails(
            id = id,
            reference = store.reference?.trim().orEmpty(),
            name = store.shopperStatement?.trim().orEmpty(),
            address =
                listOf(
                    address?.line1,
                    address?.line2,
                    address?.line3,
                    address?.city,
                    address?.stateOrProvince,
                    address?.postalCode,
                    address?.country,
                ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.joinToString("\n"),
            phone = store.phoneNumber?.trim().orEmpty(),
        )
    }

    private fun error(
        code: Int,
        text: String,
    ): String {
        val error = decodeAdyenModel(text, DefaultErrorResponseEntity::class.java)
        val message =
            when (code) {
                HTTP_UNAUTHORIZED -> {
                    "Adyen did not accept the API key"
                }

                HTTP_FORBIDDEN -> {
                    "The API key needs Management API—Stores read access to this merchant account"
                }

                else -> {
                    error
                        ?.detail
                        ?.trim()
                        ?.ifBlank { error.title?.trim() }
                        ?.ifBlank { null }
                        ?: error?.title?.trim()?.ifBlank { null } ?: "Adyen returned an error"
                }
            }
        return "$message (HTTP $code)"
    }

    /** Endpoint and bounded read policy. */
    companion object {
        private val TIMEOUT = 30.seconds
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100

        /** Management v3 endpoint for [environment], with no trailing slash. */
        fun endpoint(environment: TerminalEnvironment): String =
            when (environment) {
                TerminalEnvironment.TEST -> "https://management-test.adyen.com/v3"
                TerminalEnvironment.LIVE -> "https://management-live.adyen.com/v3"
            }
    }
}
