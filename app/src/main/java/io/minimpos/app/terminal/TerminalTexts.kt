package io.minimpos.app.terminal

/**
 * Localised setup and connection messages. English defaults keep the setup resolver usable without Android resources.
 *
 * @property poiId Missing terminal ID.
 * @property host Missing terminal address.
 * @property keyIdentifier Missing shared-key identifier.
 * @property passphrase Missing shared-key passphrase.
 * @property keyVersion Missing shared-key version.
 * @property merchantAccount Missing merchant account for the Checkout API.
 * @property apiKey Missing Checkout API key.
 * @property environment The terminal's TEST/LIVE environment has not been detected.
 * @property livePrefix Missing LIVE endpoint prefix.
 * @property unreadablePassphrase The saved shared-key passphrase cannot be decrypted.
 * @property unreadableApiKey The saved Checkout API key cannot be decrypted.
 * @property apiRequired No Checkout API credentials have been entered.
 * @property noResponse The terminal did not answer a diagnosis.
 */
data class TerminalTexts(
    val poiId: String = "Enter the terminal ID (POIID) in Terminal settings",
    val host: String = "Enter the terminal's IP address in Terminal settings",
    val keyIdentifier: String = "Enter the shared key identifier in Terminal settings",
    val passphrase: String = "Enter the shared key passphrase in Terminal settings",
    val keyVersion: String = "Enter the shared key version in Terminal settings",
    val merchantAccount: String = "Enter the merchant account in Terminal settings",
    val apiKey: String = "Enter the Checkout API key in Terminal settings",
    val environment: String = "Test the connection to the terminal first, so the app knows whether it is TEST or LIVE",
    val livePrefix: String = "Enter the live URL prefix in Terminal settings",
    val unreadablePassphrase: String =
        "The saved shared key passphrase could not be read on this device; enter it again in Terminal settings",
    val unreadableApiKey: String = "The saved Checkout API key could not be read on this device; enter it again in Terminal settings",
    val apiRequired: String = "Enter the merchant account and the Checkout API key first",
    val noResponse: String = "The terminal did not answer",
)
