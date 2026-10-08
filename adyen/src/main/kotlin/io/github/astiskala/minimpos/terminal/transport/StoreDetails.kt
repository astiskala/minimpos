package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.management.DefaultErrorResponseEntity
import com.adyen.model.management.Merchant
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
 * @property name Store shopper statement, the name Adyen prints on shopper receipts; blank if absent.
 * @property address Address components in receipt order, separated by newlines; blank if absent.
 * @property phone Store phone number, normally E.164; blank if absent.
 */
data class StoreDetails(
    val name: String,
    val address: String,
    val phone: String,
)

/** Result of reading one store's receipt details; HTTP, network and malformed-answer failures are returned, not thrown. */
sealed interface StoreLookup {
    /**
     * The requested store of the requested merchant account.
     * @property store Its receipt details.
     */
    data class Found(
        val store: StoreDetails,
    ) : StoreLookup

    /** Adyen knows no such store in the requested merchant account (HTTP 404). */
    data object Missing : StoreLookup

    /**
     * The store could not be read; no partial details are offered.
     * @property message Non-secret failure explanation.
     */
    data class Failed(
        val message: String,
    ) : StoreLookup
}

/** Result of reading a merchant account's legal name; HTTP, network and malformed-answer failures are returned, not thrown. */
sealed interface MerchantLookup {
    /**
     * The requested merchant account.
     * @property legalName Its legal name, trimmed; blank if Adyen has none.
     */
    data class Found(
        val legalName: String,
    ) : MerchantLookup

    /**
     * The merchant account could not be read.
     * @property message Non-secret failure explanation.
     */
    data class Failed(
        val message: String,
    ) : MerchantLookup
}

/** Read-only Management API store and merchant access; safe to call from any thread. */
interface StoreDetailsApi {
    /** Reads store [storeId] of [merchantAccount] with Management API—Stores read, without listing other stores. */
    suspend fun store(
        merchantAccount: String,
        storeId: String,
    ): StoreLookup

    /** Reads the legal name of [merchantAccount] with Management API—Account read; it has no receipt address or phone. */
    suspend fun merchant(merchantAccount: String): MerchantLookup
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

    override suspend fun store(
        merchantAccount: String,
        storeId: String,
    ): StoreLookup {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchantAccount)
                .addPathSegment("stores")
                .addPathSegment(storeId)
                .build()
        return when (val reply = http.get(url, TIMEOUT)) {
            is AdyenReply.Failed -> {
                StoreLookup.Failed(reply.message)
            }

            is AdyenReply.Answered -> {
                when {
                    reply.code == HTTP_NOT_FOUND -> {
                        StoreLookup.Missing
                    }

                    !reply.ok -> {
                        StoreLookup.Failed(error(reply.code, reply.body, STORES_READ))
                    }

                    else -> {
                        runCatching {
                            val store = requireNotNull(decodeAdyenModel(reply.body, Store::class.java))
                            require(store.id?.trim() == storeId)
                            StoreLookup.Found(details(store))
                        }.getOrElse { StoreLookup.Failed("Adyen sent unreadable store details") }
                    }
                }
            }
        }
    }

    override suspend fun merchant(merchantAccount: String): MerchantLookup {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchantAccount)
                .build()
        return when (val reply = http.get(url, TIMEOUT)) {
            is AdyenReply.Failed -> {
                MerchantLookup.Failed(reply.message)
            }

            is AdyenReply.Answered -> {
                if (!reply.ok) {
                    MerchantLookup.Failed(error(reply.code, reply.body, ACCOUNT_READ))
                } else {
                    runCatching {
                        val merchant = requireNotNull(decodeAdyenModel(reply.body, Merchant::class.java))
                        require(merchant.id == merchantAccount)
                        MerchantLookup.Found(merchant.name?.trim().orEmpty())
                    }.getOrElse { MerchantLookup.Failed("Adyen sent unreadable merchant details") }
                }
            }
        }
    }

    private fun details(store: Store): StoreDetails {
        val address = store.address
        return StoreDetails(
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
        role: String,
    ): String {
        val error = decodeAdyenModel(text, DefaultErrorResponseEntity::class.java)
        val message =
            when (code) {
                HTTP_UNAUTHORIZED -> {
                    "Adyen did not accept the API key"
                }

                HTTP_FORBIDDEN -> {
                    "The API key needs $role access to this merchant account"
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

    /** Endpoint, read timeout and the API key roles each read needs. */
    companion object {
        private val TIMEOUT = 30.seconds
        private const val STORES_READ = "Management API—Stores read"
        private const val ACCOUNT_READ = "Management API—Account read"

        /** Management v3 endpoint for [environment], with no trailing slash. */
        fun endpoint(environment: TerminalEnvironment): String =
            when (environment) {
                TerminalEnvironment.TEST -> "https://management-test.adyen.com/v3"
                TerminalEnvironment.LIVE -> "https://management-live.adyen.com/v3"
            }
    }
}
