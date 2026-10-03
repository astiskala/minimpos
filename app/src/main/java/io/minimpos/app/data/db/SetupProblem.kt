package io.minimpos.app.data.db

/**
 * What must be entered, installed or fixed before payments (or the Checkout API, or Tap to Pay) can work, as
 * [io.minimpos.app.terminal.TerminalSetup.resolve] and the modules that read the saved secrets find it. Typed so that
 * the screens word it (in `feature/OutcomeMessages.kt`), also when it is stored with a transaction or capture as a
 * [StoredReason.NotSetUp], so it reads in the current language. Stored by name: never rename an entry.
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

    /** No Adyen API key is saved, while the merchant account is entered. */
    API_KEY,

    /** Whether payments go to TEST or LIVE is not known yet: no connection has found it. */
    ENVIRONMENT,

    /** The live URL prefix the Checkout API needs in LIVE is not entered. */
    LIVE_PREFIX,

    /** The saved shared key passphrase cannot be decrypted on this device. */
    UNREADABLE_PASSPHRASE,

    /** The saved Adyen API key cannot be decrypted on this device. */
    UNREADABLE_API_KEY,

    /** Neither the merchant account nor the Adyen API key is entered, and the action needs them. */
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

    /** The optional Manager PIN is configured and approval has not been granted, or has expired. */
    MANAGER_APPROVAL,

    /** The current destination or Adyen account does not match the original operation. */
    PAYMENT_CONTEXT,
}
