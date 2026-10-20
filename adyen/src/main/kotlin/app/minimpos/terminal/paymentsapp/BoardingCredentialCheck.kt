package app.minimpos.terminal.paymentsapp

import app.minimpos.terminal.transport.Fault

/** Documented GET /me result for the credential used to generate boarding tokens, never its key values. */
sealed interface BoardingCredentialCheck {
    /**
     * A successful, readable Management v1 credential response; key presence does not establish payment readiness.
     * @property id Adyen's credential identifier.
     * @property username Verified credential name, not a user-entered label.
     * @property active Whether Adyen reports this credential enabled.
     * @property clientKeyPresent Whether the documented clientKey string is nonblank; its value is discarded.
     * @property hasRequiredRole Whether the readable role list contains the boarding role.
     */
    data class Verified(
        val id: String,
        val username: String,
        val active: Boolean,
        val clientKeyPresent: Boolean,
        val hasRequiredRole: Boolean,
    ) : BoardingCredentialCheck

    /**
     * No conclusion about client-key presence, including 401, 403, transport and schema failures.
     * @property fault Why the diagnostic could not run; permissions are optional for taking payments.
     */
    data class Failed(
        val fault: Fault,
    ) : BoardingCredentialCheck
}
