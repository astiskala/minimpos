package app.minimpos.app.feature.settings

import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability

/** Callbacks for Adyen-side terminal configuration, keeping composables free of view models. */
internal interface CapabilitiesEvents {
    /** Refreshes Management capability inspection. */
    fun onRefresh()

    /** Acknowledges one explicit save result. */
    fun onResultDismiss(result: CapabilityResult)

    /** Saves terminal gratuity settings for [currency]. */
    fun onSaveGratuity(
        currency: String,
        gratuity: GratuityCapability?,
    )

    /** Saves terminal DCC setting. */
    fun onSaveDcc(enabled: Boolean)

    /** Saves terminal MOTO configuration. */
    fun onSaveMoto(moto: MotoCapability)

    /** Saves terminal offline limits for [currency]. */
    fun onSaveOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    )
}

internal fun capabilitiesEvents(vm: CapabilitiesViewModel): CapabilitiesEvents =
    object : CapabilitiesEvents {
        override fun onRefresh() = vm.refresh()

        override fun onResultDismiss(result: CapabilityResult) = vm.dismissResult(result)

        override fun onSaveGratuity(
            currency: String,
            gratuity: GratuityCapability?,
        ) = vm.saveGratuity(currency, gratuity)

        override fun onSaveDcc(enabled: Boolean) = vm.saveDcc(enabled)

        override fun onSaveMoto(moto: MotoCapability) = vm.saveMoto(moto)

        override fun onSaveOffline(
            currency: String,
            chipFloorLimit: Int?,
            maxStoreAndForwardMinor: Int?,
            maxPayments: Int?,
        ) = vm.saveOffline(currency, chipFloorLimit, maxStoreAndForwardMinor, maxPayments)
    }
