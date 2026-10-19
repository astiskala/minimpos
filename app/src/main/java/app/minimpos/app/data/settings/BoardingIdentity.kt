package app.minimpos.app.data.settings

import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.serialization.Serializable

/**
 * Device-local evidence of a new boarding completed by this app, not an inference from an already-boarded response.
 * No key values or fingerprints are stored here; changing credentials never rewrites historical evidence.
 * @property environment Environment used for the boarding token request.
 * @property installationId Installation returned by the completed boarding.
 * @property merchant Merchant account supplied for that boarding.
 * @property store Store ID supplied for that boarding; null for merchant-level boarding.
 * @property credentialId GET /me identity of the credential that requested the token; null if introspection was unavailable.
 * @property username Verified credential name; null if introspection was unavailable.
 * @property boardedAt Completion timestamp, UTC epoch milliseconds.
 */
@Serializable
data class BoardingIdentity(
    val environment: TerminalEnvironment,
    val installationId: String,
    val merchant: String,
    val store: String?,
    val credentialId: String?,
    val username: String?,
    val boardedAt: Long,
)
