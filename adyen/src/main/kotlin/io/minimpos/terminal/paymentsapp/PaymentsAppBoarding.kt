package io.minimpos.terminal.paymentsapp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.minimpos.terminal.transport.AdyenHttp
import io.minimpos.terminal.transport.AdyenReply
import io.minimpos.terminal.transport.HTTP_FORBIDDEN
import io.minimpos.terminal.transport.HTTP_UNAUTHORIZED
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where a Payments app instance is boarded: a merchant account, or one of its stores. Constructing it with a blank
 * [merchantAccount] throws [IllegalArgumentException].
 *
 * @property merchantAccount The merchant account (its code, which the Management API calls `merchantId`).
 * @property storeId The store's ID (not its reference, e.g. `ST322LJ223223K5F4SQNR9XL5`); null boards the app for the
 *   merchant account.
 */
data class BoardingTarget(
    val merchantAccount: String,
    val storeId: String? = null,
) {
    init {
        require(merchantAccount.isNotBlank()) { "A merchant account is required" }
    }
}

/** The answer of a Management API request about Payments app instances. Network and HTTP errors are reported, not thrown. */
sealed interface ManagementResult {
    /**
     * Adyen did what was asked.
     *
     * @property boardingToken The one-time boarding token (valid for an hour) for a boarding token request; null for a
     *   revocation.
     */
    data class Done(
        val boardingToken: String? = null,
    ) : ManagementResult

    /**
     * Adyen refused, or could not be reached.
     *
     * @property message Why, in English.
     */
    data class Failed(
        val message: String,
    ) : ManagementResult
}

/** Adyen's Management API for Payments app instances, which needs an API key with the Adyen Payments app role. */
interface PaymentsAppManagement {
    /** Asks for a boarding token for the instance that sent [boardingRequestToken], to board it at [target]. */
    suspend fun boardingToken(
        target: BoardingTarget,
        boardingRequestToken: String,
    ): ManagementResult

    /** Revokes the instance [installationId] of [merchantAccount], so it can no longer take payments. */
    suspend fun revoke(
        merchantAccount: String,
        installationId: String,
    ): ManagementResult
}

/**
 * [PaymentsAppManagement] over HTTPS with OkHttp. The API key goes in the `x-api-key` header and is never part of a
 * message. Calls run on [dispatcher]; cancelling the coroutine interrupts them.
 *
 * @param apiKey An API key with the Adyen Payments app role.
 * @param environment Which Management API to call.
 * @param baseUrl Where the API lives; tests point it at a local server.
 * @param baseClient The client to derive from, so an app can share one connection pool.
 * @param dispatcher Where the blocking calls run.
 */
class AdyenPaymentsAppManagement(
    apiKey: String,
    environment: TerminalEnvironment,
    private val baseUrl: HttpUrl = endpoint(environment).toHttpUrl(),
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PaymentsAppManagement {
    private val http = AdyenHttp(apiKey, baseClient, dispatcher)

    override suspend fun boardingToken(
        target: BoardingTarget,
        boardingRequestToken: String,
    ): ManagementResult {
        val store = target.storeId?.let { listOf("stores", it) }.orEmpty()
        val path = listOf("merchants", target.merchantAccount) + store + "generatePaymentsAppBoardingToken"
        return post(path, JsonObject().apply { addProperty("boardingRequestToken", boardingRequestToken) })
    }

    override suspend fun revoke(
        merchantAccount: String,
        installationId: String,
    ): ManagementResult = post(listOf("merchants", merchantAccount, "paymentsApps", installationId, "revoke"), JsonObject())

    private suspend fun post(
        path: List<String>,
        body: JsonObject,
    ): ManagementResult =
        when (val reply = http.post(baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build(), body.toString(), TIMEOUT)) {
            is AdyenReply.Answered -> result(reply)

            // Boarding tokens and revocations can be asked for again, so a request that may have arrived needs no special care.
            is AdyenReply.Failed -> ManagementResult.Failed(reply.message)
        }

    private fun result(reply: AdyenReply.Answered): ManagementResult {
        val code = reply.code
        val json = runCatching { JsonParser.parseString(reply.body).asJsonObject }.getOrNull()
        if (reply.ok) return ManagementResult.Done(json?.get("boardingToken")?.takeIf { it.isJsonPrimitive }?.asString)
        val detail = json?.get("detail") ?: json?.get("title")
        val message =
            detail?.takeIf { it.isJsonPrimitive }?.asString
                ?: when (code) {
                    HTTP_UNAUTHORIZED -> "Adyen did not accept the Payments app API key"
                    HTTP_FORBIDDEN -> "The API key lacks the Adyen Payments app role, or may not use this merchant account"
                    else -> "Adyen returned an error"
                }
        return ManagementResult.Failed("$message (HTTP $code)")
    }

    /** The Management API's endpoints. */
    companion object {
        private val TIMEOUT = 30.seconds

        /** The Management API's base URL for [environment], with its version and without a trailing slash. */
        fun endpoint(environment: TerminalEnvironment): String =
            when (environment) {
                TerminalEnvironment.TEST -> "https://management-test.adyen.com/v1"
                TerminalEnvironment.LIVE -> "https://management-live.adyen.com/v1"
            }
    }
}

/** The outcome of [PaymentsAppOnboarding.board]. */
sealed interface Onboarding {
    /**
     * The Payments app is boarded.
     *
     * @property installationId The instance's ID, which is the POIID of every request to it.
     */
    data class Boarded(
        val installationId: String,
    ) : Onboarding

    /**
     * It is not boarded.
     *
     * @property message Why, in English (from the Payments app or the Management API).
     */
    data class Failed(
        val message: String,
    ) : Onboarding
}

/**
 * Boards the Adyen Payments app on this phone, as Adyen describes it: ask whether it is boarded (`/boarded`), get a
 * boarding token for the instance from the Management API, and hand that to the Payments app (`/board`). Each step
 * opens the Payments app, which calls back at `<returnUrl>/boarded` and `<returnUrl>/board`.
 *
 * @param environment Which Payments app to board.
 * @param exchange Opens the links.
 * @param management The Management API, with the Payments app API key.
 * @param returnUrl Where the Payments app calls back, without a trailing slash.
 */
class PaymentsAppOnboarding(
    environment: TerminalEnvironment,
    private val exchange: AppLinkExchange,
    private val management: PaymentsAppManagement,
    private val returnUrl: String,
) {
    private val links = PaymentsAppLinks(environment)

    /**
     * Boards the Payments app at [target], or reports the instance it is already boarded with; with [reboard] it is
     * boarded afresh (another merchant account or store). Network and Payments app errors are reported in the outcome;
     * a Payments app that cannot be started throws as [AppLinkExchange.exchange] does.
     */
    suspend fun board(
        target: BoardingTarget,
        reboard: Boolean = false,
    ): Onboarding {
        val check = open(links.boardedCheck("$returnUrl/$BOARDED", reboard))
        val token = check["boardingRequestToken"]
        return when {
            check["boarded"] == "true" && !reboard -> {
                boarded(check)
            }

            token.isNullOrEmpty() -> {
                failed(check)
            }

            else -> {
                when (val result = management.boardingToken(target, token)) {
                    is ManagementResult.Failed -> Onboarding.Failed(result.message)
                    is ManagementResult.Done -> finish(result.boardingToken)
                }
            }
        }
    }

    private suspend fun finish(boardingToken: String?): Onboarding {
        if (boardingToken.isNullOrEmpty()) return Onboarding.Failed("Adyen sent no boarding token")
        val answer = open(links.board(boardingToken, "$returnUrl/$BOARD"))
        return if (answer["boarded"] == "true") boarded(answer) else failed(answer)
    }

    private fun boarded(answer: Map<String, String>): Onboarding =
        answer["installationId"]?.takeIf { it.isNotEmpty() }?.let { Onboarding.Boarded(it) }
            ?: Onboarding.Failed("The Payments app sent no installation ID")

    private fun failed(answer: Map<String, String>): Onboarding =
        Onboarding.Failed("The Payments app is not boarded: ${answer["error"] ?: "no reason given"}")

    private suspend fun open(link: String): Map<String, String> =
        PaymentsAppLinks.answer(exchange.exchange(link, links.packageName, STEP_TIMEOUT))

    private companion object {
        /** The path of the return URL the boarding check answers on. */
        const val BOARDED = "boarded"

        /** The path of the return URL boarding answers on. */
        const val BOARD = "board"

        /** How long the operator may take in the Payments app at each step. */
        val STEP_TIMEOUT = 5.minutes
    }
}
