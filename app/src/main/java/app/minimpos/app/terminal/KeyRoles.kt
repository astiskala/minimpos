package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRolesApi
import app.minimpos.terminal.transport.CredentialRolesUpdate
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment

/**
 * Roles an API key lacks and Mini mPOS can add to it once the operator agrees.
 *
 * @property roles The roles to add.
 */
data class KeyRolesOffer(
    val roles: List<AdyenRole>,
) {
    /** The roles by the names the Customer Area gives them. */
    val names: List<String> get() = roles.map { it.displayName }
}

/** Whether this failure is an Adyen API key lacking roles, which [KeyRoles] may be able to add. */
fun Failure.lacksKeyRoles(): Boolean = this is Failure.Remote && (fault as? Fault.Permission)?.key == ApiKey.ADYEN

/** Offers and adds the roles an API key lacks: a widening of what the key can do, so only after the operator agrees. */
internal object KeyRoles {
    /** What [lookup] found missing, when the key can add it: it must itself hold the role that edits credentials. */
    fun offer(lookup: CredentialLookup.Failed): KeyRolesOffer? {
        val missing = lookup.roles?.missing.orEmpty()
        return KeyRolesOffer(missing).takeIf {
            lookup.fault is Fault.Permission && missing.isNotEmpty() && AdyenRole.API_CREDENTIALS !in missing
        }
    }

    /** Adds [offer]'s roles to the key behind [api]; null once they are confirmed, else why Adyen refused or failed. */
    suspend fun add(
        api: CredentialRolesApi,
        environment: TerminalEnvironment,
        offer: KeyRolesOffer,
    ): Fault? =
        when (val update = api.addRoles(environment, offer.roles)) {
            CredentialRolesUpdate.Added -> null
            is CredentialRolesUpdate.Failed -> update.fault
        }
}
