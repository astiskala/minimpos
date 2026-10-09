package app.minimpos.app.data.db

/**
 * What must be entered, installed or fixed before payments (or the Checkout API, or Tap to Pay) can work, as
 * [app.minimpos.app.terminal.TerminalSetup.resolve] and the modules that read the saved secrets find it. Typed so that
 * the screens word it (in `feature/OutcomeMessages.kt`), also when it is stored with a transaction or capture as a
 * [StoredReason.NotDone], so it reads in the current language. Stored by name: never rename an entry.
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

    /** TEST or LIVE has not been selected for a network or cloud terminal. */
    ENVIRONMENT,

    /** This device's terminal certificate could not yet supply its environment. */
    TERMINAL_ENVIRONMENT,

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

    /** Setup has not passed credential and connection checks for its current fields and secrets. */
    SETUP_NOT_VERIFIED,

    /** A verified import is still being committed; retry its durable recovery before taking payments. */
    TRANSFER_PENDING,

    /** Credential cannot access the selected terminal; its Company account or terminal access must be corrected. */
    TERMINAL_ACCESS,

    /** Explicit account conflicts with the terminal's current assignment. */
    MERCHANT_MISMATCH,

    /** Credential lacks the terminal-access role required for every real setup. */
    MANAGEMENT_PERMISSION,

    /** API credential is rejected in the device's selected or detected environment. */
    MANAGEMENT_AUTHENTICATION,

    /** Management lookup failed temporarily or returned an unreadable answer. */
    MANAGEMENT_UNAVAILABLE,

    /** A successful Management answer lacks required fields or contains malformed data. */
    MANAGEMENT_UNREADABLE,

    /** Terminal settings contain unsupported fields or invalid values, preventing safe shared-key lookup or creation. */
    TERMINAL_SETTINGS_UNREADABLE,

    /** Adyen returned an encryption-key object without all required fields; never treat it as key absence. */
    SHARED_KEY_INCOMPLETE,

    /** Adyen returned an encryption-key version outside the supported range. */
    SHARED_KEY_INVALID,

    /** The assigned receipt store could not be retrieved; another store must not be substituted. */
    STORE_ACCESS,

    /** Configuration changed while a candidate was being verified. */
    SETUP_CHANGED,

    /** A created key is available at Adyen, but encrypted terminal communication has not been verified. */
    KEY_CONNECTION_PENDING,

    /** The encrypted key-creation record cannot be read; never replace it or generate another key. */
    KEY_RECOVERY_UNREADABLE,

    /** Key creation may have reached Adyen without confirmed read-back; explicit recovery must read first. */
    KEY_CREATION_UNCONFIRMED,
}
