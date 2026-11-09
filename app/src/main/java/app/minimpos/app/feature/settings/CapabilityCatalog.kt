package app.minimpos.app.feature.settings

import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.payment.Cashback
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.UnreferencedRefundBlock
import app.minimpos.app.payment.UnreferencedRefunds
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityStanding
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.giftcard.GiftCardProvider

/** The gift-card types Adyen lists that a provider sends without a merchant-configured code. */
private val FIXED_GIFT_CARD_TYPES = GiftCardProvider.entries.mapNotNull(GiftCardProvider::wireCode).toSet()

/** Stable task groups; changing a feature's status never moves another supported feature. */
internal enum class CapabilityGroup {
    PAYMENTS,
    SHOPPER,
    RECEIPTS,
    TERMINAL,
}

/**
 * Feature navigation keys, independent of localized labels and cached remote readings.
 * @property group Stable task group.
 * @property editorResult Scoped save action, or null for features configured elsewhere.
 */
internal enum class CapabilityFeature(
    val group: CapabilityGroup = CapabilityGroup.PAYMENTS,
    val editorResult: CapabilityResult? = null,
) {
    PARTIAL_AUTH,
    CARD_SAVING,
    SAVED_CARDS,
    PAYMENT_LINKS,
    GIFT_CARDS,
    SCANNED_WALLETS,
    MEAL_VOUCHERS,
    MEXICAN_PLANS,
    CASHBACK,
    CASHOUT,
    UNREFERENCED_REFUNDS,
    MOTO(editorResult = CapabilityResult.MOTO),
    MKE,
    DCC(editorResult = CapabilityResult.DCC),
    SURCHARGE,
    TIPPING(CapabilityGroup.SHOPPER, CapabilityResult.GRATUITY),
    RECEIPT_TIPPING(CapabilityGroup.SHOPPER),
    GIVING(CapabilityGroup.SHOPPER),
    AUDIO_GUIDANCE(CapabilityGroup.SHOPPER),
    SHOPPER_DISPLAY(CapabilityGroup.SHOPPER),
    PRINTING(CapabilityGroup.RECEIPTS),
    SCANNER(CapabilityGroup.RECEIPTS),
    OFFLINE(CapabilityGroup.TERMINAL, CapabilityResult.OFFLINE),
    RECONCILIATION(CapabilityGroup.TERMINAL),
    ABORT(CapabilityGroup.TERMINAL),
    DIAGNOSIS(CapabilityGroup.TERMINAL),
    REMOTE_STATUS(CapabilityGroup.TERMINAL),
    ;

    /** Whether this feature has a scoped Management editor, rather than only a local setting or explanation. */
    val editableAtAdyen: Boolean get() = editorResult != null
}

/** Setup indicators, not proof that a particular shopper, basket or card will be accepted. */
internal enum class CapabilityStatus {
    READY,
    OFF,
    NEEDS_SETUP,
    UNVERIFIED,
    UNSUPPORTED,
}

/** The next setup task, kept typed until presentation; [LOCAL_ONLY] is a local switch whose Adyen side is already set up. */
internal enum class CapabilityRequirement {
    NONE,
    LOCAL,
    LOCAL_ONLY,
    ADYEN,
    REFRESH,
    STORE,
    REFERENCE,
    PROVIDER,
    PRINTER,
    SUPPORT,
    UNSUPPORTED,
}

/**
 * One feature's setup reading; transaction-specific eligibility remains with the payment decision owners.
 * @property feature Stable navigation key.
 * @property status What is established for the current settings.
 * @property requirement The next task, or [CapabilityRequirement.NONE] when no setup task is known.
 */
internal data class CapabilityItem(
    val feature: CapabilityFeature,
    val status: CapabilityStatus,
    val requirement: CapabilityRequirement = CapabilityRequirement.NONE,
)

/**
 * Pure presentation of current capability evidence, local preferences and the existing payment rules. This never
 * authorizes a payment or infers store country from currency. Manager approval is checked again by each operation.
 */
internal class CapabilityCatalog(
    private val settings: AppSettings,
    private val terminal: TerminalState,
    private val remote: AdyenCapabilitiesState,
    private val wallets: WalletAvailability,
    private val currency: String,
) {
    private val payment get() = settings.payment
    private val discovered get() = remote.checked && remote.problem == null && remote.failure == null
    private val storeChecked get() = wallets.checked && wallets.problem == null && wallets.failure == null

    /** Whether Adyen lists a gift-card type for the store that one of the fixed-code providers sends. */
    private val giftCardsAtAdyen: Boolean
        get() = discovered && remote.methodsChecked && remote.giftCardsFor(currency).any { it in FIXED_GIFT_CARD_TYPES }

    // Keep the catalog explicit and complete: the catalog tests enumerate every navigation key.
    private val readings: Map<CapabilityFeature, () -> Pair<CapabilityStatus, CapabilityRequirement>> =
        mapOf(
            CapabilityFeature.PARTIAL_AUTH to { local(payment.allowPartialAuthorisation) },
            CapabilityFeature.CARD_SAVING to ::cardSaving,
            CapabilityFeature.SAVED_CARDS to ::savedCards,
            CapabilityFeature.PAYMENT_LINKS to ::paymentLinks,
            CapabilityFeature.GIFT_CARDS to ::giftCards,
            CapabilityFeature.SCANNED_WALLETS to ::scannedWallets,
            CapabilityFeature.MEAL_VOUCHERS to ::mealVouchers,
            CapabilityFeature.MEXICAN_PLANS to ::mexicanPlans,
            CapabilityFeature.CASHBACK to { cashback(standalone = false) },
            CapabilityFeature.CASHOUT to { cashback(standalone = true) },
            CapabilityFeature.UNREFERENCED_REFUNDS to ::unreferencedRefunds,
            CapabilityFeature.MOTO to ::moto,
            CapabilityFeature.MKE to ::mke,
            CapabilityFeature.DCC to { remoteSwitch(remote.dccEnabled) },
            CapabilityFeature.SURCHARGE to ::surcharge,
            CapabilityFeature.TIPPING to ::tipping,
            CapabilityFeature.RECEIPT_TIPPING to ::receiptTipping,
            CapabilityFeature.GIVING to ::giving,
            CapabilityFeature.AUDIO_GUIDANCE to ::audioGuidance,
            CapabilityFeature.SHOPPER_DISPLAY to ::shopperDisplay,
            CapabilityFeature.PRINTING to ::printing,
            CapabilityFeature.SCANNER to { fromStanding(remote.terminalScannerStanding) },
            CapabilityFeature.OFFLINE to ::offline,
            CapabilityFeature.RECONCILIATION to { supported(terminal.capabilities.supportsTerminalFeatures) { ready() } },
            CapabilityFeature.ABORT to { supported(terminal.capabilities.aborts) { ready() } },
            CapabilityFeature.DIAGNOSIS to { fromStanding(remote.diagnosisStanding) },
            CapabilityFeature.REMOTE_STATUS to ::remoteStatus,
        )

    /** Every Adyen feature in stable order. App-only features and security settings do not belong here. */
    fun items(): List<CapabilityItem> = CapabilityFeature.entries.map(::item)

    /** Reads [feature] without changing any preference or contacting Adyen. */
    fun item(feature: CapabilityFeature): CapabilityItem {
        if (!terminal.loaded) return CapabilityItem(feature, CapabilityStatus.UNVERIFIED, CapabilityRequirement.REFRESH)
        val result = readings.getValue(feature).invoke()
        return CapabilityItem(feature, result.first, result.second)
    }

    private fun mke() =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            when {
                !payment.allowMke -> off()
                remote.simulated -> ready()
                else -> CapabilityStatus.UNVERIFIED to CapabilityRequirement.SUPPORT
            }
        }

    private fun printing() =
        when {
            !terminal.capabilities.supportsPrinting || terminal.printingStanding == CapabilityStanding.UNSUPPORTED -> unsupported()
            settings.receipt.printerMode == PrinterMode.OFF -> off()
            else -> fromStanding(terminal.printingStanding)
        }

    private fun cardSaving() =
        when {
            !payment.offerCardSaving -> off()
            payment.shopperReferenceSource == ShopperReferenceSource.NONE -> needs(CapabilityRequirement.REFERENCE)
            terminal.apiProblem != null -> needs(CapabilityRequirement.ADYEN)
            else -> ready()
        }

    private fun savedCards() = if (terminal.apiProblem != null) needs(CapabilityRequirement.ADYEN) else ready()

    private fun paymentLinks() = if (terminal.paymentLinks) ready() else needs(CapabilityRequirement.ADYEN)

    private fun giftCards() =
        supported(terminal.capabilities.giftCards) {
            when {
                payment.giftCards.isEmpty() -> offUnless(giftCardsAtAdyen)

                payment.giftCards.any { !it.complete } -> needs(CapabilityRequirement.PROVIDER)

                !discovered || !remote.methodsChecked -> unverified()

                !remote.simulated &&
                    payment.offeredGiftCards.any { gift ->
                        remote.giftCardsFor(currency).none { it.equals(gift.wireCode, ignoreCase = true) }
                    }
                -> needs(CapabilityRequirement.ADYEN)

                else -> ready()
            }
        }

    private fun scannedWallets() =
        supported(terminal.capabilities.scannedWallets) {
            when {
                !storeChecked -> unverified()
                wallets.offered(currency).isEmpty() -> needs(CapabilityRequirement.ADYEN)
                !wallets.simulated && !remote.cameraAvailable && !wallets.nativeScanner -> needs(CapabilityRequirement.PROVIDER)
                else -> ready()
            }
        }

    private fun mealVouchers() =
        supported(terminal.capabilities.mealVouchers) {
            when {
                !payment.mealVouchers -> off()
                !storeChecked -> unverified()
                wallets.mealVoucherSchemes(currency).isEmpty() -> needs(CapabilityRequirement.STORE)
                else -> ready()
            }
        }

    private fun mexicanPlans() =
        supported(terminal.capabilities.mexicanInstallments) {
            when {
                !payment.mexicanInstallments -> off()
                !storeChecked -> unverified()
                !Checkout.offersMexicanRouting(payment, wallets.mexicanRouting, currency) -> needs(CapabilityRequirement.STORE)
                else -> ready()
            }
        }

    private fun moto() =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            when {
                !payment.allowMoto -> offUnless(discovered && remote.moto.enabled)
                !discovered -> unverified()
                !remote.moto.enabled -> needs(CapabilityRequirement.ADYEN)
                else -> ready()
            }
        }

    private fun tipping() =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            when {
                payment.terminalTipping == ReceiptTipping.DISABLED -> offUnless(discovered && remote.gratuityFor(currency) != null)
                payment.dynamicTipOverride && terminal.tipOptionsAvailable -> ready()
                !discovered -> unverified()
                remote.gratuityFor(currency) == null -> needs(CapabilityRequirement.ADYEN)
                else -> ready()
            }
        }

    private fun receiptTipping() =
        supported(terminal.capabilities.supportsPrinting && terminal.printingStanding != CapabilityStanding.UNSUPPORTED) {
            when {
                payment.receiptTipping == ReceiptTipping.DISABLED -> off()
                terminal.printingStanding != CapabilityStanding.AVAILABLE -> needs(CapabilityRequirement.PRINTER)
                else -> ready()
            }
        }

    private fun giving() =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            when {
                payment.askGiving == ReceiptTipping.DISABLED -> off()
                remote.simulated -> ready()
                else -> CapabilityStatus.UNVERIFIED to CapabilityRequirement.SUPPORT
            }
        }

    private fun audioGuidance() =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            when {
                !remote.simulated && !terminal.audioGuidanceAvailable -> {
                    if (terminal.audioGuidanceMinimumSoftware != null && terminal.softwareVersion == null) unverified() else unsupported()
                }

                !payment.audioGuidance -> {
                    off()
                }

                remote.simulated -> {
                    ready()
                }

                else -> {
                    CapabilityStatus.UNVERIFIED to CapabilityRequirement.SUPPORT
                }
            }
        }

    private fun shopperDisplay() =
        supported(terminal.capabilities.supportsShopperDisplay) {
            when {
                payment.showVirtualReceipt || payment.confirmBasketOnTerminal || payment.askShopperRating ||
                    payment.showTerminalQrReceipt || (payment.collectEmailOnTerminal && settings.emailReceiptsReady) -> ready()

                payment.collectEmailOnTerminal -> needs(CapabilityRequirement.LOCAL)

                else -> off()
            }
        }

    private fun offline() =
        supported(terminal.capabilities.supportsOfflinePayments) {
            if (!discovered) {
                unverified()
            } else if (remote.offline.configuredFor(currency)) {
                ready()
            } else {
                off(CapabilityRequirement.ADYEN)
            }
        }

    private fun remoteStatus() =
        when (terminal.capabilities.remoteStatus) {
            CapabilityStanding.AVAILABLE -> ready()
            CapabilityStanding.NEEDS_CONFIGURATION -> CapabilityStatus.UNVERIFIED to CapabilityRequirement.SUPPORT
            CapabilityStanding.UNSUPPORTED -> unsupported()
        }

    private fun cashback(standalone: Boolean): Pair<CapabilityStatus, CapabilityRequirement> =
        if (wallets.context == null) {
            unverified()
        } else {
            supported(wallets.cashbackDestination) {
                when {
                    !(if (standalone) payment.cashback.standalone else payment.cashback.enabled) -> off()
                    !storeChecked -> unverified()
                    !Cashback.ready(payment.cashback, wallets, currency, standalone) -> needs(CapabilityRequirement.STORE)
                    else -> ready()
                }
            }
        }

    private fun unreferencedRefunds(): Pair<CapabilityStatus, CapabilityRequirement> =
        if (wallets.context == null) {
            unverified()
        } else {
            when (UnreferencedRefunds.block(payment.unreferencedRefunds, wallets, currency)) {
                UnreferencedRefundBlock.DESTINATION -> unsupported()
                UnreferencedRefundBlock.COUNTRY -> CapabilityStatus.UNSUPPORTED to CapabilityRequirement.STORE
                UnreferencedRefundBlock.NOT_ENABLED -> off()
                UnreferencedRefundBlock.NO_LIMIT -> needs(CapabilityRequirement.LOCAL)
                UnreferencedRefundBlock.NOT_VERIFIED -> unverified()
                UnreferencedRefundBlock.CHANGED -> needs(CapabilityRequirement.STORE)
                null -> ready()
            }
        }

    // The simulator stands in for Adyen's surcharge rules with its own setting, so the demo can show a reported fee.
    private fun surcharge() =
        if (remote.simulated) {
            supported(terminal.capabilities.supportsTerminalFeatures) { local(settings.simulator.surchargeMinor > 0) }
        } else {
            remoteSwitch(remote.surchargeConfigured)
        }

    private fun remoteSwitch(enabled: Boolean): Pair<CapabilityStatus, CapabilityRequirement> =
        supported(terminal.capabilities.supportsTerminalFeatures) {
            if (!discovered) {
                unverified()
            } else if (enabled) {
                ready()
            } else {
                off(CapabilityRequirement.ADYEN)
            }
        }

    private fun fromStanding(standing: CapabilityStanding): Pair<CapabilityStatus, CapabilityRequirement> =
        when {
            standing == CapabilityStanding.UNSUPPORTED -> unsupported()
            standing == CapabilityStanding.NEEDS_CONFIGURATION -> unverified()
            else -> ready()
        }

    private fun local(enabled: Boolean): Pair<CapabilityStatus, CapabilityRequirement> = if (enabled) ready() else off()

    private inline fun supported(
        supported: Boolean,
        reading: () -> Pair<CapabilityStatus, CapabilityRequirement>,
    ): Pair<CapabilityStatus, CapabilityRequirement> = if (supported) reading() else unsupported()

    private fun ready() = CapabilityStatus.READY to CapabilityRequirement.NONE

    private fun off(requirement: CapabilityRequirement = CapabilityRequirement.LOCAL) = CapabilityStatus.OFF to requirement

    /** Off locally; when [configuredAtAdyen], only the local switch is missing. */
    private fun offUnless(configuredAtAdyen: Boolean) =
        off(if (configuredAtAdyen) CapabilityRequirement.LOCAL_ONLY else CapabilityRequirement.LOCAL)

    private fun needs(requirement: CapabilityRequirement) = CapabilityStatus.NEEDS_SETUP to requirement

    private fun unverified() = CapabilityStatus.UNVERIFIED to CapabilityRequirement.REFRESH

    private fun unsupported() = CapabilityStatus.UNSUPPORTED to CapabilityRequirement.UNSUPPORTED
}
