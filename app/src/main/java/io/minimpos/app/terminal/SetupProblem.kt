package io.minimpos.app.terminal

/**
 * What must be entered, installed or fixed before payments (or the Checkout API, or Tap to Pay) can work, as
 * [TerminalSetup.resolve] and the modules that read the saved secrets find it. Typed so that the screens word it (in
 * `feature/OutcomeMessages.kt`); messages that are stored with a transaction are worded by the container's
 * [TerminalSetupSource.describe].
 */
enum class SetupProblem {
    /** No terminal ID (POIID) is entered. */
    POI_ID,

    /** No IP address of the terminal on the network is entered. */
    HOST,

    /** No shared key identifier is entered. */
    KEY_IDENTIFIER,

    /** No shared key passphrase is saved. */
    PASSPHRASE,

    /** The shared key version is not valid. */
    KEY_VERSION,

    /** No merchant account is entered. */
    MERCHANT_ACCOUNT,

    /** No Checkout API key is saved, while the merchant account is entered. */
    API_KEY,

    /** Whether payments go to TEST or LIVE is not known yet: no connection has found it. */
    ENVIRONMENT,

    /** The live URL prefix the Checkout API needs in LIVE is not entered. */
    LIVE_PREFIX,

    /** The saved shared key passphrase cannot be decrypted on this device. */
    UNREADABLE_PASSPHRASE,

    /** The saved Checkout API key cannot be decrypted on this device. */
    UNREADABLE_API_KEY,

    /** Neither the merchant account nor the Checkout API key is entered, and the action needs them. */
    API_REQUIRED,

    /** No API key is saved for a terminal in the cloud. */
    CLOUD_API_KEY,

    /** The Adyen Payments app is not installed. */
    PAYMENTS_APP_MISSING,

    /** Both the TEST and the LIVE Payments app are installed, so the environment is ambiguous. */
    PAYMENTS_APP_AMBIGUOUS,

    /** The Payments app has not been boarded on this phone yet (no installation ID). */
    PAYMENTS_APP_NOT_BOARDED,

    /** The Payments app was chosen on a payment terminal, where it does not run. */
    PAYMENTS_APP_ON_TERMINAL,

    /** No Payments app API key (for boarding) is saved. */
    PAYMENTS_APP_API_KEY,

    /** The saved Payments app API key cannot be decrypted on this device. */
    UNREADABLE_PAYMENTS_APP_KEY,
}
