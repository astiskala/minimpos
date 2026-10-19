package app.minimpos.app.feature

/** Localized independent client-key and boarding readings; never a statement of payment readiness. */
internal data class TapToPayLabels(
    val clientKey: String,
    val boarding: String,
)
