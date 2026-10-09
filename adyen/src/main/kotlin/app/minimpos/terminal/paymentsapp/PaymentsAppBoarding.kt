package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.transport.AdyenError
import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.decodeAdyenModel
import app.minimpos.terminal.transport.fault
import com.adyen.model.paymentsapp.BoardingTokenRequest
import com.adyen.model.paymentsapp.BoardingTokenResponse
import com.adyen.model.paymentsapp.DefaultErrorResponseEntity
import com.adyen.model.paymentsapp.PaymentsAppResponse
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

    /** Adyen lists no boarded Payments app with that installation ID for the merchant account or store. */
    data object NotBoarded : ManagementResult

    /**
     * Adyen refused, could not be reached, or answered with something unusable.
     *
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : ManagementResult
}

/** Adyen's Management API for Payments app instances, which needs an API key with the Adyen Payments app role. */
interface PaymentsAppManagement {
    /** Verifies that [installationId] is currently boarded for [target], without changing registration. */
    suspend fun registration(
        target: BoardingTarget,
        installationId: String,
    ): ManagementResult = ManagementResult.Failed(Fault.Unsupported)

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

    override suspend fun registration(
        target: BoardingTarget,
        installationId: String,
    ): ManagementResult {
        val path = listOf("merchants", target.merchantAccount) + target.storeId?.let { listOf("stores", it) }.orEmpty() + "paymentsApps"
        var page = 0
        var found: ManagementResult? = null
        while (page < MAX_PAGES && found == null) {
            val url =
                baseUrl
                    .newBuilder()
                    .apply { path.forEach { addPathSegment(it) } }
                    .addQueryParameter("limit", PAGE_SIZE.toString())
                    .addQueryParameter("offset", (page++ * PAGE_SIZE).toString())
                    .addQueryParameter("statuses", "BOARDED")
                    .build()
            found =
                when (val reply = http.get(url, TIMEOUT)) {
                    is AdyenReply.Failed -> ManagementResult.Failed(reply.fault)
                    is AdyenReply.Answered -> if (reply.ok) registrationPage(reply.body, target, installationId) else result(reply)
                }
        }
        return found ?: ManagementResult.Failed(Fault.ListTooLarge)
    }

    private fun registrationPage(
        body: String,
        target: BoardingTarget,
        installationId: String,
    ): ManagementResult? {
        val apps =
            runCatching {
                requireNotNull(decodeAdyenModel(body, PaymentsAppResponse::class.java)?.paymentsApps).map { requireNotNull(it) }
            }.getOrNull()
        return when {
            apps == null -> {
                ManagementResult.Failed(Fault.UnreadableReply())
            }

            apps.any {
                it.installationId == installationId && it.merchantAccountCode == target.merchantAccount && it.status == "BOARDED"
            } -> {
                ManagementResult
                    .Done()
            }

            apps.size < PAGE_SIZE -> {
                ManagementResult.NotBoarded
            }

            else -> {
                null
            }
        }
    }

    override suspend fun boardingToken(
        target: BoardingTarget,
        boardingRequestToken: String,
    ): ManagementResult {
        val store = target.storeId?.let { listOf("stores", it) }.orEmpty()
        val path = listOf("merchants", target.merchantAccount) + store + "generatePaymentsAppBoardingToken"
        return post(path, BoardingTokenRequest().boardingRequestToken(boardingRequestToken).toJson())
    }

    override suspend fun revoke(
        merchantAccount: String,
        installationId: String,
    ): ManagementResult = post(listOf("merchants", merchantAccount, "paymentsApps", installationId, "revoke"), "{}")

    private suspend fun post(
        path: List<String>,
        body: String,
    ): ManagementResult =
        when (val reply = http.post(baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build(), body, TIMEOUT)) {
            is AdyenReply.Answered -> result(reply)

            // Boarding tokens and revocations can be asked for again, so a request that may have arrived needs no special care.
            is AdyenReply.Failed -> ManagementResult.Failed(reply.fault)
        }

    private fun result(reply: AdyenReply.Answered): ManagementResult {
        if (reply.ok) return ManagementResult.Done(decodeAdyenModel(reply.body, BoardingTokenResponse::class.java)?.boardingToken)
        val error = decodeAdyenModel(reply.body, DefaultErrorResponseEntity::class.java)
        val said = ExternalText.of(error?.detail) ?: ExternalText.of(error?.title)
        return ManagementResult.Failed(reply.fault(ApiKey.PAYMENTS_APP, PAYMENTS_APP_ROLE, AdyenError(said, error?.errorCode)))
    }

    /** The Management API's endpoints. */
    companion object {
        private val TIMEOUT = 30.seconds
        private const val PAYMENTS_APP_ROLE = "Adyen Payments app role"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100

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
     * @property fault Why (from the Payments app, the exchange with it, or the Management API).
     */
    data class Failed(
        val fault: Fault,
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
     * boarded afresh (another merchant account or store). Network and Payments app errors, and a Payments app that
     * cannot be started, are reported in the outcome.
     */
    suspend fun board(
        target: BoardingTarget,
        reboard: Boolean = false,
    ): Onboarding {
        val check =
            when (val answer = open(links.boardedCheck("$returnUrl/$BOARDED", reboard))) {
                is Step.Answered -> answer.fields
                is Step.Failed -> return Onboarding.Failed(answer.fault)
            }
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
                    is ManagementResult.Failed -> Onboarding.Failed(result.fault)
                    ManagementResult.NotBoarded -> Onboarding.Failed(Fault.UnreadableReply())
                    is ManagementResult.Done -> finish(result.boardingToken)
                }
            }
        }
    }

    private suspend fun finish(boardingToken: String?): Onboarding {
        if (boardingToken.isNullOrEmpty()) return Onboarding.Failed(Fault.UnreadableReply())
        val answer =
            when (val step = open(links.board(boardingToken, "$returnUrl/$BOARD"))) {
                is Step.Answered -> step.fields
                is Step.Failed -> return Onboarding.Failed(step.fault)
            }
        return if (answer["boarded"] == "true") boarded(answer) else failed(answer)
    }

    private fun boarded(answer: Map<String, String>): Onboarding =
        answer["installationId"]?.takeIf { it.isNotEmpty() }?.let { Onboarding.Boarded(it) }
            ?: Onboarding.Failed(Fault.UnreadableReply())

    private fun failed(answer: Map<String, String>): Onboarding = Onboarding.Failed(Fault.AppRefused(ExternalText.of(answer["error"])))

    private suspend fun open(link: String): Step =
        when (val answer = exchange.exchange(link, links.packageName, STEP_TIMEOUT)) {
            is AppLinkAnswer.Returned -> Step.Answered(PaymentsAppLinks.answer(answer.url))
            is AppLinkAnswer.Unanswered -> Step.Failed(answer.fault)
        }

    /** What one step in the Payments app answered. */
    private sealed interface Step {
        data class Answered(
            val fields: Map<String, String>,
        ) : Step

        data class Failed(
            val fault: Fault,
        ) : Step
    }

    private companion object {
        /** The path of the return URL the boarding check answers on. */
        const val BOARDED = "boarded"

        /** The path of the return URL boarding answers on. */
        const val BOARD = "board"

        /** How long the operator may take in the Payments app at each step. */
        val STEP_TIMEOUT = 5.minutes
    }
}
