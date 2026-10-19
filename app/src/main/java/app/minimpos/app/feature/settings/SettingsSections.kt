package app.minimpos.app.feature.settings

/** Keys of the Settings sub-screens (navigation arguments and test tags). */
object SettingsSections {
    /** Where payments go, the shared key and the connection test. */
    const val TERMINAL = "terminal"

    /** Progressive Adyen terminal capabilities and scoped Management configuration. */
    const val CAPABILITIES = "capabilities"

    /** How the simulator behaves; only offered when payments go to it. */
    const val SIMULATOR = "simulator"

    /** Currency, references, card entry, saving cards and payment-link expiry. */
    const val PAYMENTS = "payments"

    /** Checkout defaults for terminal tips, receipt tips and Adyen Giving. */
    const val TIPPING = "tipping"

    /** Optional interactions on the shopper-facing screen of a separate terminal. */
    const val SHOPPER_DISPLAY = "shopper_display"

    /** Tax rates, tax-inclusive prices and "Charge tax". */
    const val TAX = "tax"

    /** Receipt content, printing and the merchant copy. */
    const val RECEIPTS = "receipts"

    /** Email collection, automatic receipt delivery, SMTP settings and the test email. */
    const val EMAIL = "email"

    /** Admin PIN, auto-lock and the separate Manager PIN. */
    const val SECURITY = "security"

    /** History retention, clearing history, sample data and setup transfer. */
    const val DATA = "data"

    /** User guides, issue reporting and device troubleshooting. */
    const val HELP = "help"

    /** App, device and terminal details. */
    const val ABOUT = "about"
}
