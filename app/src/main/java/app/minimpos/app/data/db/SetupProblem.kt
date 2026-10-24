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

    /** The selected environment's Adyen Payments app is not installed. */
    PAYMENTS_APP_MISSING,

    /** The Payments app has not been boarded on this phone yet (no installation ID). */
    PAYMENTS_APP_NOT_BOARDED,

    /** The Payments app was chosen on a payment terminal, where it does not run. */
    PAYMENTS_APP_ON_TERMINAL,

    /** Tap to Pay setup has not created this phone's own credential (for boarding) yet. */
    PAYMENTS_APP_API_KEY,

    /** This phone's own credential (for boarding) cannot be decrypted on this device. */
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

    /** Printing is not offered: the printer setting is off, or the terminal (or simulator) has no printer. */
    PRINTER,

    /**
     * A saved-card charge or Checkout refund whose outcome is unknown is older than Adyen keeps idempotency keys, so
     * sending it again could act twice; it is checked in the Customer Area instead.
     */
    CHARGE_TOO_OLD,

    /** Adyen did not permit this HTTP error to be retried; reconcile the original operation externally. */
    OPERATION_RECONCILIATION,

    /** A token deletion must be reconciled before another token can be charged or deleted. */
    TOKEN_DELETION_PENDING,

    /** No shopper reference is configured (Settings › Payments), so no saved card can be found or charged. */
    SHOPPER_REFERENCE,

    /** The destination cannot report terminal totals or close a reconciliation period (Tap to Pay). */
    TOTALS,

    /** Original sale/card balance changed after refund review; the request was not sent. */
    REFUND_BALANCE,
}
