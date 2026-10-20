package app.minimpos.app.terminal

import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.Serializable

/**
 * This phone's own Tap to Pay credential, or the intent to create it, kept encrypted on this device only
 * ([TerminalSetupSource.boardingCredentials]) and never transferred.
 *
 * @property owner Fingerprint of the Adyen API key that creates it ([BoardingOwner.Ready.fingerprint]), never the key.
 * @property merchant The merchant account it is created for.
 * @property environment The Payments app environment it is created in.
 * @property reference Its unique description at Adyen, by which an interrupted creation is recovered.
 * @property apiKey Its API key; null until the creation is confirmed, when this exact creation must be recovered.
 */
@Serializable
internal class BoardingCredential(
    val owner: String,
    val merchant: String,
    val environment: TerminalEnvironment,
    val reference: String,
    val apiKey: String? = null,
) {
    /** Whether it is (or will be) created by [key] for its merchant account and environment. */
    fun matches(key: BoardingOwner.Ready): Boolean =
        owner == key.fingerprint && merchant == key.merchantAccount && environment == key.environment
}
