package app.minimpos.app.data.db

import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault

/**
 * Why an action did not happen, or was not confirmed: the one failure shape every result type carries, worded only by
 * the screens (`feature/OutcomeMessages.kt`) in the current language. Whether a money-moving action may still have
 * taken effect is said by the result that carries it, not here. It is stored with a transaction or capture inside a
 * [StoredReason].
 */
sealed interface Failure {
    /**
     * Nothing was sent, because something must be entered, installed, approved or reconciled first.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : Failure

    /**
     * An exchange with Adyen, a terminal or the Payments app produced no usable result.
     *
     * @property fault Why.
     */
    data class Remote(
        val fault: Fault,
    ) : Failure

    /**
     * Something failed on this device.
     *
     * @property fault What.
     */
    data class Device(
        val fault: DeviceFault,
    ) : Failure

    /**
     * An email could not be sent.
     *
     * @property fault Why.
     * @property reply The mail server's reply; null when there was none.
     */
    data class Email(
        val fault: EmailFault,
        val reply: ExternalText? = null,
    ) : Failure
}

/** What failed on the device running Mini mPOS; exception text is never kept. Stored by name: never rename an entry. */
enum class DeviceFault {
    /** Android's secure storage (keystore) could not encrypt, decrypt or save a secret. */
    SECURE_STORAGE,

    /** The local database could not be read or written. */
    DATABASE,

    /** A local file could not be written or read, such as when storage is full. */
    FILE_STORAGE,

    /** Mini mPOS failed in a way it did not expect. */
    UNEXPECTED,
}

/** Why an email was not sent. Stored by name: never rename an entry. */
enum class EmailFault {
    /** Email is not set up in Settings. */
    NOT_CONFIGURED,

    /** The recipient address is not valid. */
    INVALID_ADDRESS,

    /** The mail server did not accept the username or password. */
    AUTHENTICATION,

    /** The mail server could not be reached, or did not answer in time. */
    UNREACHABLE,

    /** The mail server refused the message or a recipient. */
    REJECTED,
}
