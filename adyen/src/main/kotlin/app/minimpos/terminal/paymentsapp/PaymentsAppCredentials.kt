package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MANAGEMENT_PAGE_SIZE
import app.minimpos.terminal.transport.ManagementPages
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.collectManagementPages
import app.minimpos.terminal.transport.decodeAdyenModel
import app.minimpos.terminal.transport.managementEndpoint
import app.minimpos.terminal.transport.managementFault
import com.adyen.model.management.ApiCredential
import com.adyen.model.management.CreateApiCredentialResponse
import com.adyen.model.management.CreateMerchantApiCredentialRequest
import com.adyen.model.management.GenerateApiKeyResponse
import com.adyen.model.management.GenerateClientKeyResponse
import com.adyen.model.management.ListMerchantApiCredentialsResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/** Credential provisioning result; secret values never appear in [toString]. */
sealed interface ProvisionedCredential {
    /**
     * Dedicated merchant-scoped boarding credential, with a client key already provisioned.
     * @property apiKey Secret API key to persist before requesting boarding tokens.
     */
    class Ready(
        val apiKey: String,
    ) : ProvisionedCredential

    /**
     * Provisioning did not finish; an uncertain creation must be recovered, not repeated.
     * @property fault Transport, permission or response failure.
     * @property creationMayExist Whether creation needs recovery, including a later known client-key failure.
     */
    data class Failed(
        val fault: Fault,
        val creationMayExist: Boolean = fault.mayHaveTakenEffect,
    ) : ProvisionedCredential
}

/** Uses the primary credential to provision a separate Payments app credential, never a Customer Area user. */
fun interface PaymentsAppCredentials {
    /**
     * Creates a credential named [reference] for [merchant]. With [recover], first recovers that exact creation: reads
     * all pages, then replaces its unretrieved API key; only when the complete listing has no credential named
     * [reference] is it created again. Never rotates an unrelated credential.
     */
    suspend fun provision(
        merchant: String,
        reference: String,
        recover: Boolean,
    ): ProvisionedCredential
}

/**
 * Merchant-level Management v3 provisioning through the primary API key.
 * @param apiKey Primary key with API credentials read and write.
 * @param environment Selected Payments app environment.
 * @param baseUrl Management v3 endpoint; tests use a local server.
 * @param baseClient Shared connection pool.
 * @param dispatcher Dispatcher for blocking HTTP.
 */
class AdyenPaymentsAppCredentials(
    apiKey: String,
    environment: TerminalEnvironment,
    private val baseUrl: HttpUrl = managementEndpoint(environment).toHttpUrl(),
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PaymentsAppCredentials {
    private val http = AdyenHttp(apiKey, baseClient, dispatcher)

    override suspend fun provision(
        merchant: String,
        reference: String,
        recover: Boolean,
    ): ProvisionedCredential {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("merchants")
                .addPathSegment(merchant)
                .addPathSegment("apiCredentials")
                .build()
        val matches =
            if (recover) {
                when (val pages = collectManagementPages(role = ROLE, fetch = { page(url, it) }, parse = ::parsePage)) {
                    is ManagementPages.Failed -> return ProvisionedCredential.Failed(pages.fault)
                    is ManagementPages.Listed -> pages.items.filter { it.description == reference }
                }
            } else {
                emptyList()
            }
        return if (matches.isEmpty()) create(url, reference) else recover(url, matches)
    }

    private suspend fun create(
        url: HttpUrl,
        reference: String,
    ): ProvisionedCredential {
        val body =
            CreateMerchantApiCredentialRequest()
                .description(reference)
                .roles(listOf(PAYMENTS_APP_ROLE, AdyenRole.ACCOUNT_READ.displayName))
                .toJson()
        val reply = http.post(url, body, TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return failed(reply)
        val created = decodeAdyenModel(reply.body, CreateApiCredentialResponse::class.java)
        val id = created?.id?.takeIf { it.isNotBlank() } ?: return failed(reply)
        val key = created.apiKey?.takeIf { it.isNotBlank() } ?: return failed(reply)
        if (created.active != true || PAYMENTS_APP_ROLE !in created.roles.orEmpty()) return failed(reply)
        return finish(url.newBuilder().addPathSegment(id).build(), key, created.clientKey)
    }

    private suspend fun page(
        url: HttpUrl,
        number: Int,
    ): AdyenReply =
        http.get(
            url
                .newBuilder()
                .addQueryParameter("pageNumber", number.toString())
                .addQueryParameter("pageSize", MANAGEMENT_PAGE_SIZE.toString())
                .build(),
            TIMEOUT,
        )

    private fun parsePage(body: String): Pair<List<ApiCredential>, Boolean>? =
        runCatching {
            val response = requireNotNull(decodeAdyenModel(body, ListMerchantApiCredentialsResponse::class.java))
            requireNotNull(response.data).map { requireNotNull(it) } to
                !response.links
                    ?.next
                    ?.href
                    .isNullOrBlank()
        }.getOrNull()

    /** Replaces the unretrieved API key of the one credential in [matches], which a creation left without its answer. */
    private suspend fun recover(
        url: HttpUrl,
        matches: List<ApiCredential>,
    ): ProvisionedCredential {
        val found = matches.singleOrNull()?.takeIf { it.active == true && PAYMENTS_APP_ROLE in it.roles.orEmpty() }
        val id = found?.id?.takeIf { it.isNotBlank() } ?: return ProvisionedCredential.Failed(Fault.UnreadableReply())
        val credentialUrl = url.newBuilder().addPathSegment(id).build()
        val reply = http.post(credentialUrl.newBuilder().addPathSegment("generateApiKey").build(), "{}", TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return failed(reply)
        val key =
            decodeAdyenModel(reply.body, GenerateApiKeyResponse::class.java)?.apiKey?.takeIf { it.isNotBlank() } ?: return failed(reply)
        return finish(credentialUrl, key, found.clientKey)
    }

    private suspend fun finish(
        url: HttpUrl,
        apiKey: String,
        clientKey: String?,
    ): ProvisionedCredential {
        if (clientKey == null) return ProvisionedCredential.Failed(Fault.UnreadableReply())
        val fault = if (clientKey.isBlank()) generateClientKey(url) else null
        return fault?.let { ProvisionedCredential.Failed(it, creationMayExist = true) } ?: ProvisionedCredential.Ready(apiKey)
    }

    private suspend fun generateClientKey(url: HttpUrl): Fault? {
        val reply = http.post(url.newBuilder().addPathSegment("generateClientKey").build(), "{}", TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return managementFault(reply, role = ROLE)
        return if (decodeAdyenModel(reply.body, GenerateClientKeyResponse::class.java)?.clientKey.isNullOrBlank()) {
            Fault.UnreadableReply()
        } else {
            null
        }
    }

    private fun failed(reply: AdyenReply): ProvisionedCredential.Failed = ProvisionedCredential.Failed(managementFault(reply, role = ROLE))

    private companion object {
        private val ROLE = AdyenRole.API_CREDENTIALS.displayName
        private val TIMEOUT = 30.seconds
    }
}
