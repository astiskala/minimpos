package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MANAGEMENT_PAGE_SIZE
import app.minimpos.terminal.transport.MAX_MANAGEMENT_PAGES
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.decodeAdyenModel
import app.minimpos.terminal.transport.decodeMeCredential
import app.minimpos.terminal.transport.managementEndpoint
import app.minimpos.terminal.transport.managementFault
import com.adyen.model.paymentsapp.BoardingTokenRequest
import com.adyen.model.paymentsapp.BoardingTokenResponse
import com.adyen.model.paymentsapp.PaymentsAppResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The Customer Area name of the API credential role that boards and revokes the Payments app. */
internal const val PAYMENTS_APP_ROLE = "Adyen Payments App role"

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
     *   revocation or registration check.
     * @property installationId The installation the boarding token was issued for; null for a revocation or
     *   registration check, or when Adyen named none.
     */
    data class Done(
        val boardingToken: String? = null,
        val installationId: String? = null,
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

/** Adyen's Management API for Payments app instances, which needs an API key with the Adyen Payments App role. */
interface PaymentsAppManagement {
    /** Read-only introspection of this boarding credential; no client/API key value escapes the adapter. */
    suspend fun credential(): BoardingCredentialCheck = BoardingCredentialCheck.Failed(Fault.Unsupported)

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
 * @param apiKey An API key with the Adyen Payments App role.
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

    override suspend fun credential(): BoardingCredentialCheck {
        val reply = http.get(baseUrl.newBuilder().addPathSegment("me").build(), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) {
            return BoardingCredentialCheck.Failed(managementFault(reply, ApiKey.PAYMENTS_APP))
        }
        return runCatching {
            val credential = requireNotNull(decodeMeCredential(reply.body))
            requireNotNull(credential.roles).forEach { requireNotNull(it) }
            requireNotNull(credential.allowedIpAddresses).forEach { requireNotNull(it) }
            BoardingCredentialCheck.Verified(
                id = requireNotNull(credential.id?.takeIf { it.isNotBlank() }),
                username = requireNotNull(credential.username?.takeIf { it.isNotBlank() }),
                active = requireNotNull(credential.active),
                // v1 requires a string: absent/null is an unreadable schema, not confirmed key absence.
                clientKeyPresent = requireNotNull(credential.clientKey).isNotBlank(),
                hasRequiredRole = PAYMENTS_APP_ROLE in credential.roles,
            )
        }.getOrNull() ?: BoardingCredentialCheck.Failed(Fault.UnreadableReply())
    }

    override suspend fun registration(
        target: BoardingTarget,
        installationId: String,
    ): ManagementResult {
        val path = listOf("merchants", target.merchantAccount) + target.storeId?.let { listOf("stores", it) }.orEmpty() + "paymentsApps"
        var page = 0
        var found: ManagementResult? = null
        while (page < MAX_MANAGEMENT_PAGES && found == null) {
            val url =
                baseUrl
                    .newBuilder()
                    .apply { path.forEach { addPathSegment(it) } }
                    .addQueryParameter("limit", MANAGEMENT_PAGE_SIZE.toString())
                    .addQueryParameter("offset", (page++ * MANAGEMENT_PAGE_SIZE).toString())
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

            apps.size < MANAGEMENT_PAGE_SIZE -> {
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

    private fun result(reply: AdyenReply.Answered): ManagementResult =
        if (reply.ok) {
            val issued = decodeAdyenModel(reply.body, BoardingTokenResponse::class.java)
            ManagementResult.Done(issued?.boardingToken, issued?.installationId?.takeIf { it.isNotEmpty() })
        } else {
            ManagementResult.Failed(managementFault(reply, ApiKey.PAYMENTS_APP, PAYMENTS_APP_ROLE))
        }

    /** The Management API's endpoints. */
    companion object {
        private val TIMEOUT = 30.seconds

        /** The Management API's base URL for [environment], with its version and without a trailing slash. */
        fun endpoint(environment: TerminalEnvironment): String = managementEndpoint(environment, version = 1)
    }
}

/** The outcome of [PaymentsAppOnboarding.board]. */
sealed interface Onboarding {
    /** The Payments app is boarded; where (which merchant account or store) depends on how. */
    sealed interface Boarded : Onboarding {
        /** The instance's ID, which is the POIID of every request to it. */
        val installationId: String
    }

    /**
     * It was boarded already, so it took no boarding token: only the Management API's list of boarded instances tells
     * whether it is boarded at the target, or at another merchant account or store (or was revoked at Adyen meanwhile).
     *
     * @property installationId The instance's ID.
     */
    data class AlreadyBoarded(
        override val installationId: String,
    ) : Boarded

    /**
     * It was boarded just now, with a boarding token Adyen issued for the target.
     *
     * @property installationId The instance's ID.
     * @property confirmed Whether Adyen issued the token for this very installation, which confirms where it is boarded
     *   without the Management API's list (that list can lag behind boarding); false when Adyen named no or another
     *   installation.
     */
    data class NewlyBoarded(
        override val installationId: String,
        val confirmed: Boolean,
    ) : Boarded

    /**
     * It is not boarded at the target.
     *
     * @property fault Why (from the Payments app, the exchange with it, or the Management API).
     * @property unboarded Whether the Payments app may have no boarding now: it answered that it was not boarded, or
     *   reboarding has started (or may have, when the first step went unanswered) and dropped the one it had. An
     *   installation ID kept for it may then take no payments.
     */
    data class Failed(
        val fault: Fault,
        val unboarded: Boolean,
    ) : Onboarding
}

/**
 * Boards the Adyen Payments app on this phone, as Adyen describes it: ask whether it is boarded (`/boarded`), get a
 * boarding token for the instance from the Management API, and hand that to the Payments app (`/board`). Each step
 * opens the Payments app, which calls back at `<returnUrl>/boarded` and `<returnUrl>/board`.
 *
 * @param environment Which Payments app to board.
 * @param exchange Opens the links.
 * @param management The Management API, with this phone's own credential for boarding.
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
     * boarded afresh (another merchant account or store), which drops its earlier boarding as soon as the Payments app
     * answers the first step. Network and Payments app errors, and a Payments app that cannot be started, are reported
     * in the outcome.
     */
    suspend fun board(
        target: BoardingTarget,
        reboard: Boolean = false,
    ): Onboarding {
        val check =
            when (val answer = open(links.boardedCheck("$returnUrl/$BOARDED", reboard))) {
                is Step.Answered -> answer.fields
                is Step.Failed -> return Onboarding.Failed(answer.fault, unboarded = reboard && answer.fault.mayHaveTakenEffect)
            }
        if (check["boarded"] == "true" && !reboard) {
            return check.installationId()?.let(Onboarding::AlreadyBoarded) ?: Onboarding.Failed(Fault.UnreadableReply(), unboarded = false)
        }
        val token = check["boardingRequestToken"]
        if (token.isNullOrEmpty()) return failed(check, unboarded = reboard || check["boarded"] == "false")
        return when (val issued = management.boardingToken(target, token)) {
            is ManagementResult.Failed -> Onboarding.Failed(issued.fault, unboarded = true)
            ManagementResult.NotBoarded -> Onboarding.Failed(Fault.UnreadableReply(), unboarded = true)
            is ManagementResult.Done -> finish(issued)
        }
    }

    private suspend fun finish(issued: ManagementResult.Done): Onboarding {
        val boardingToken = issued.boardingToken
        if (boardingToken.isNullOrEmpty()) return Onboarding.Failed(Fault.UnreadableReply(), unboarded = true)
        val answer =
            when (val step = open(links.board(boardingToken, "$returnUrl/$BOARD"))) {
                is Step.Answered -> step.fields
                is Step.Failed -> return Onboarding.Failed(step.fault, unboarded = true)
            }
        if (answer["boarded"] != "true") return failed(answer, unboarded = true)
        val id = answer.installationId() ?: return Onboarding.Failed(Fault.UnreadableReply(), unboarded = true)
        return Onboarding.NewlyBoarded(id, confirmed = id == issued.installationId)
    }

    private fun Map<String, String>.installationId(): String? = get("installationId")?.takeIf { it.isNotEmpty() }

    private fun failed(
        answer: Map<String, String>,
        unboarded: Boolean,
    ): Onboarding = Onboarding.Failed(Fault.AppRefused(ExternalText.of(answer["error"])), unboarded)

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
        private val STEP_TIMEOUT = 5.minutes
    }
}
