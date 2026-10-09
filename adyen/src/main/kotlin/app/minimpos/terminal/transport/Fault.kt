package app.minimpos.terminal.transport

/**
 * Words written by Adyen, a terminal, the Adyen Payments app or a mail server, kept exactly as they came: never
 * translated, reworded or used to decide anything. Text the app writes itself is never wrapped in it.
 *
 * @property text The words as received, trimmed.
 */
@JvmInline
value class ExternalText(
    val text: String,
) {
    override fun toString(): String = text

    /** Wrapping text that may be absent. */
    companion object {
        /** [text] trimmed and wrapped; null when it is null or blank. */
        fun of(text: String?): ExternalText? = text?.trim()?.takeIf { it.isNotEmpty() }?.let(::ExternalText)
    }
}

/** Which API key a request was sent with, so a refusal names the one to check. */
enum class ApiKey {
    /** The Adyen API key used for Checkout, cloud terminals and Management reads. */
    ADYEN,

    /** The separate API key used only to register (board) and revoke the Payments app for Tap to Pay. */
    PAYMENTS_APP,
}

/** Which part of a successful Management answer could not be used. */
enum class MalformedPart {
    /** Terminal settings contain unsupported fields or values that cannot be preserved safely in a change. */
    SETTINGS,

    /** An encryption-key object lacks its identifier, version or passphrase; it is never read as an absent key. */
    KEY,

    /** An encryption-key object has a version outside the supported range. */
    KEY_VERSION,
}

/**
 * Why an exchange with Adyen, a terminal or the Adyen Payments app produced no usable result. Each case says once
 * whether the request may have reached its destination and taken effect ([mayHaveTakenEffect]), which decides whether
 * a transaction is reported as not processed or has its outcome established first. Faults are typed so the app words
 * them in the operator's language; only the [ExternalText] they carry is shown verbatim. A decline is not a fault: the
 * terminal answered.
 */
sealed interface Fault {
    /**
     * Whether the request may have reached its destination and taken effect, so its outcome is unknown until it is
     * established; false when it certainly took no effect.
     */
    val mayHaveTakenEffect: Boolean

    /** A fault after which the request certainly took no effect. */
    sealed interface NotSent : Fault {
        override val mayHaveTakenEffect: Boolean get() = false
    }

    /** A fault after which the request may have taken effect. */
    sealed interface MaybeSent : Fault {
        override val mayHaveTakenEffect: Boolean get() = true
    }

    /**
     * Nothing answered at [host]: the connection was refused or there is no route to it.
     *
     * @property host The host (with its port when it matters) that was called.
     * @property terminal Whether it is a terminal on the network rather than Adyen.
     */
    data class Unreachable(
        val host: String,
        val terminal: Boolean,
    ) : NotSent

    /**
     * The name [host] could not be looked up.
     *
     * @property host The host name that was called.
     * @property terminal Whether it is a terminal on the network rather than Adyen.
     */
    data class UnknownHost(
        val host: String,
        val terminal: Boolean,
    ) : NotSent

    /**
     * The device at [host] did not present a valid Adyen terminal certificate for the expected environment.
     *
     * @property host The host that was called.
     */
    data class Untrusted(
        val host: String,
    ) : NotSent

    /**
     * The terminal refused the request because of the shared key.
     *
     * @property said What the terminal said; null when it said nothing.
     */
    data class KeyRejected(
        val said: ExternalText?,
    ) : NotSent

    /**
     * The terminal refused the request for another reason.
     *
     * @property said What the terminal said; null when it said nothing.
     */
    data class TerminalRejected(
        val said: ExternalText?,
    ) : NotSent

    /**
     * Adyen reports that the cloud terminal [poiId] is not connected, so the request never reached it.
     *
     * @property poiId The terminal's POIID.
     * @property said What Adyen said; null when it said nothing.
     */
    data class TerminalOffline(
        val poiId: String,
        val said: ExternalText?,
    ) : NotSent

    /** The Adyen Payments app could not be opened. */
    data object NotStarted : NotSent

    /**
     * The Payments app answered without taking the request.
     *
     * @property said Its error; null when it gave none.
     */
    data class AppRefused(
        val said: ExternalText?,
    ) : NotSent

    /** The destination does not take this kind of request. */
    data object Unsupported : NotSent

    /** The Payments app cannot be asked for a result, and no late reply to the transaction has arrived. */
    data object NoLateReply : NotSent

    /** The terminal has no record of the transaction a status check asked about, so it was not processed. */
    data object NoRecord : NotSent

    /** The request could not be encrypted with the shared key. */
    data object RequestNotEncrypted : NotSent

    /**
     * Adyen did not accept the API key (HTTP 401).
     *
     * @property key Which key was sent.
     */
    data class Credential(
        val key: ApiKey,
    ) : NotSent

    /**
     * The API key lacks a role, or access to the merchant account or store (HTTP 403, or a role missing from its
     * credential).
     *
     * @property key Which key was sent.
     * @property role The Adyen role it needs, in Adyen's own words; null when unknown.
     */
    data class Permission(
        val key: ApiKey,
        val role: String? = null,
    ) : NotSent

    /**
     * Adyen does not know the requested terminal in the merchant account (HTTP 404).
     *
     * @property poiId The terminal asked for; null when not a terminal.
     */
    data class NotFound(
        val poiId: String?,
    ) : NotSent

    /**
     * Adyen rejected the request (an HTTP 4xx other than 401, 403, 404, 408 and 429).
     *
     * @property http The HTTP status code.
     * @property errorCode Adyen's error code; null when it sent none.
     * @property said Adyen's error message; null when it sent none.
     */
    data class AdyenRejected(
        val http: Int,
        val errorCode: String? = null,
        val said: ExternalText? = null,
    ) : NotSent

    /** A list was too long to read completely, so nothing partial was used. */
    data object ListTooLarge : NotSent

    /** No answer came back within the request's time limit. */
    data object TimedOut : MaybeSent

    /** The connection broke, or failed in another way, before an answer came back. */
    data object ConnectionLost : MaybeSent

    /** The terminal's reply failed decryption or its integrity check, which usually means a shared key mismatch. */
    data object ReplyUnverified : MaybeSent

    /**
     * An answer came back that could not be read or did not belong to the request.
     *
     * @property said What the answer said that explains it (such as an unexpected status); null when nothing does.
     */
    data class UnreadableReply(
        val said: ExternalText? = null,
    ) : MaybeSent

    /**
     * A successful Management answer contained data that cannot be used safely.
     *
     * @property part Which part.
     */
    data class Malformed(
        val part: MalformedPart,
    ) : MaybeSent

    /**
     * Adyen forwarded the request to the cloud terminal [poiId] but got no answer from it.
     *
     * @property poiId The terminal's POIID.
     * @property said What Adyen said; null when it said nothing.
     */
    data class NoAnswerFromTerminal(
        val poiId: String,
        val said: ExternalText?,
    ) : MaybeSent

    /** The operator came back from the Payments app before it answered. */
    data object Abandoned : MaybeSent

    /**
     * The terminal answered with an HTTP error.
     *
     * @property code The HTTP status code.
     */
    data class TerminalHttp(
        val code: Int,
    ) : MaybeSent

    /**
     * Adyen timed out, throttled the request or had a server error (HTTP 408, 429 or 5xx).
     *
     * @property http The HTTP status code.
     * @property errorCode Adyen's error code; null when it sent none.
     * @property said Adyen's error message; null when it sent none.
     */
    data class AdyenUnavailable(
        val http: Int,
        val errorCode: String? = null,
        val said: ExternalText? = null,
    ) : MaybeSent

    /** The terminal reports the transaction as still in progress (the shopper or the issuer is not done yet). */
    data object StillInProgress : MaybeSent
}
