package app.minimpos.terminal.transport

import com.adyen.model.management.ListStoresResponse
import com.adyen.model.management.Merchant
import com.adyen.model.management.Store
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Receipt fields supplied by an Adyen store; blank fields are unavailable, not instructions to erase saved text.
 *
 * @property name Store shopper statement, the name Adyen prints on shopper receipts; blank if absent.
 * @property address Country-formatted address with an English country name; blank if absent.
 * Unknown countries keep the supplied components and country text on separate lines.
 * @property phone Store phone in international display format; unrecognized numbers stay verbatim, trimmed.
 * Blank if absent.
 */
data class StoreDetails(
    val name: String,
    val address: String,
    val phone: String,
)

/**
 * One merchant-scoped store choice; display fields are blank when absent.
 * @property id Adyen store ID used for boarding.
 * @property reference Merchant's store reference.
 * @property description Merchant's display description.
 */
data class StoreChoice(
    val id: String,
    val reference: String,
    val description: String,
)

/** Complete store listing, or a failure without partial choices. */
sealed interface StoreListing {
    /**
     * Stores from all Management pages, in server order.
     * @property stores Available stores; empty when none exist.
     */
    data class Found(
        val stores: List<StoreChoice>,
    ) : StoreListing

    /**
     * Listing could not finish.
     * @property fault Permission, transport or malformed-response failure.
     */
    data class Failed(
        val fault: Fault,
    ) : StoreListing
}

/** Result of reading one store's receipt details; HTTP, network and malformed-answer failures are returned, not thrown. */
sealed interface StoreLookup {
    /**
     * The requested store of the requested merchant account.
     * @property store Its receipt details.
     */
    data class Found(
        val store: StoreDetails,
    ) : StoreLookup

    /** Adyen knows no such store in the requested merchant account (HTTP 404 or Management entity-not-found code). */
    data object Missing : StoreLookup

    /**
     * The store could not be read; no partial details are offered.
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
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
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : MerchantLookup
}

/** Read-only Management API store and merchant access; safe to call from any thread. */
interface StoreDetailsApi {
    /** Lists all stores for [merchantAccount] with [AdyenRole.STORES_READ], never across environments. */
    suspend fun stores(merchantAccount: String): StoreListing

    /** Reads store [storeId] of [merchantAccount] with [AdyenRole.STORES_READ], without listing other stores. */
    suspend fun store(
        merchantAccount: String,
        storeId: String,
    ): StoreLookup

    /** Reads the legal name of [merchantAccount] with [AdyenRole.ACCOUNT_READ]; it has no receipt address or phone. */
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

    override suspend fun stores(merchantAccount: String): StoreListing =
        when (
            val pages =
                collectManagementPages(
                    role = AdyenRole.STORES_READ.displayName,
                    fetch = { storesPage(merchantAccount, it) },
                    parse = ::parseStoresPage,
                )
        ) {
            is ManagementPages.Listed -> StoreListing.Found(pages.items)
            is ManagementPages.Failed -> StoreListing.Failed(pages.fault)
        }

    private suspend fun storesPage(
        merchantAccount: String,
        number: Int,
    ): AdyenReply {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchantAccount)
                .addPathSegment("stores")
                .addQueryParameter("pageNumber", number.toString())
                .addQueryParameter("pageSize", MANAGEMENT_PAGE_SIZE.toString())
                .build()
        return http.get(url, TIMEOUT)
    }

    private fun parseStoresPage(body: String): Pair<List<StoreChoice>, Boolean>? {
        val response = decodeAdyenModel(body, ListStoresResponse::class.java)
        val data = response?.data
        if (data == null || data.any { it.id.isNullOrBlank() }) return null
        return data.map { StoreChoice(checkNotNull(it.id), it.reference.orEmpty(), it.description.orEmpty()) } to
            !response.links
                ?.next
                ?.href
                .isNullOrBlank()
    }

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
                StoreLookup.Failed(reply.fault)
            }

            is AdyenReply.Answered -> {
                when {
                    reply.code == HTTP_NOT_FOUND || managementEntityMissing(reply) -> {
                        StoreLookup.Missing
                    }

                    !reply.ok -> {
                        StoreLookup.Failed(managementFault(reply, role = AdyenRole.STORES_READ.displayName))
                    }

                    else -> {
                        runCatching {
                            val store = requireNotNull(decodeAdyenModel(reply.body, Store::class.java))
                            require(store.id?.trim() == storeId)
                            StoreLookup.Found(details(store))
                        }.getOrElse { StoreLookup.Failed(Fault.UnreadableReply()) }
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
                MerchantLookup.Failed(reply.fault)
            }

            is AdyenReply.Answered -> {
                if (!reply.ok) {
                    MerchantLookup.Failed(managementFault(reply, role = AdyenRole.ACCOUNT_READ.displayName))
                } else {
                    runCatching {
                        val merchant = requireNotNull(decodeAdyenModel(reply.body, Merchant::class.java))
                        require(merchant.id == merchantAccount)
                        MerchantLookup.Found(merchant.name?.trim().orEmpty())
                    }.getOrElse { MerchantLookup.Failed(Fault.UnreadableReply()) }
                }
            }
        }
    }

    private fun details(store: Store): StoreDetails {
        val address = store.address
        return StoreDetails(
            name = store.shopperStatement?.trim().orEmpty(),
            address = StoreAddress.format(address),
            phone = phone(store.phoneNumber, address?.country),
        )
    }

    private fun phone(
        raw: String?,
        country: String?,
    ): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return ""
        val phones = PhoneNumberUtil.getInstance()
        val parsed =
            try {
                phones.parse(text, country?.trim()?.uppercase(Locale.ROOT).orEmpty())
            } catch (_: NumberParseException) {
                return text
            }
        return if (phones.isValidNumber(parsed)) {
            phones.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } else {
            text
        }
    }

    /** Endpoint and read timeout. */
    companion object {
        private val TIMEOUT = 30.seconds

        /** Management v3 endpoint for [environment], with no trailing slash. */
        fun endpoint(environment: TerminalEnvironment): String = managementEndpoint(environment)
    }
}
