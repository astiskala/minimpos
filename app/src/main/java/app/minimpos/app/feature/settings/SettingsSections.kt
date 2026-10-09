package app.minimpos.app.feature.settings

/** Keys of the Settings sub-screens (navigation arguments and test tags). */
object SettingsSections {
    /** Where payments go, the shared key and the connection test. */
    const val TERMINAL = "terminal"

    /** How the simulator behaves; only offered when payments go to it. */
    const val SIMULATOR = "simulator"

    /** Currency, references, email capture and saving cards. */
    const val PAYMENTS = "payments"

    /** Tax rates, tax-inclusive prices and "Charge tax". */
    const val TAX = "tax"

    /** Receipt content, printing and the merchant copy. */
    const val RECEIPTS = "receipts"

    /** SMTP settings and the test email. */
    const val EMAIL = "email"

    /** The admin PIN and auto-lock. */
    const val SECURITY = "security"

    /** History retention, clearing history and catalogue transfer. */
    const val DATA = "data"

    /** App, device and terminal details. */
    const val ABOUT = "about"
}
