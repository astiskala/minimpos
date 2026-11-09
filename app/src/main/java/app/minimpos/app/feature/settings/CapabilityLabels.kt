package app.minimpos.app.feature.settings

import androidx.annotation.StringRes
import app.minimpos.app.R

/** Stable save-button identifiers shared by the four focused editors and their UI regressions. */
internal val CapabilityResult.saveTag: String
    get() =
        when (this) {
            CapabilityResult.GRATUITY -> "saveCapGratuity"
            CapabilityResult.DCC -> "saveCapDcc"
            CapabilityResult.MOTO -> "saveCapMoto"
            CapabilityResult.OFFLINE -> "saveCapOffline"
        }

/** Localized names are presentation only; feature navigation never depends on them. */
@get:StringRes
internal val CapabilityFeature.titleRes: Int
    get() =
        when (this) {
            CapabilityFeature.PARTIAL_AUTH -> R.string.capability_partial_auth
            CapabilityFeature.CARD_SAVING -> R.string.capability_card_saving
            CapabilityFeature.SAVED_CARDS -> R.string.manager_action_saved_card_charge
            CapabilityFeature.PAYMENT_LINKS -> R.string.settings_payment_links
            CapabilityFeature.GIFT_CARDS -> R.string.capability_gift_cards
            CapabilityFeature.SCANNED_WALLETS -> R.string.capability_scanned_wallets
            CapabilityFeature.MEAL_VOUCHERS -> R.string.voucher_title
            CapabilityFeature.MEXICAN_PLANS -> R.string.mexican_title
            CapabilityFeature.CASHBACK -> R.string.cashback_title
            CapabilityFeature.CASHOUT -> R.string.cashout_title
            CapabilityFeature.UNREFERENCED_REFUNDS -> R.string.unreferenced_settings_title
            CapabilityFeature.MOTO -> R.string.checkout_moto
            CapabilityFeature.MKE -> R.string.checkout_mke
            CapabilityFeature.DCC -> R.string.capability_dcc
            CapabilityFeature.SURCHARGE -> R.string.capability_surcharge
            CapabilityFeature.TIPPING -> R.string.capability_terminal_tipping
            CapabilityFeature.RECEIPT_TIPPING -> R.string.capability_receipt_tipping
            CapabilityFeature.GIVING -> R.string.capability_giving
            CapabilityFeature.AUDIO_GUIDANCE -> R.string.checkout_audio_guidance
            CapabilityFeature.SHOPPER_DISPLAY -> R.string.capability_shopper_display
            CapabilityFeature.PRINTING -> R.string.capability_printing
            CapabilityFeature.SCANNER -> R.string.capability_terminal_scanner
            CapabilityFeature.OFFLINE -> R.string.capability_offline
            CapabilityFeature.RECONCILIATION -> R.string.capability_reconciliation
            CapabilityFeature.ABORT -> R.string.capability_abort
            CapabilityFeature.DIAGNOSIS -> R.string.capability_diagnosis
            CapabilityFeature.REMOTE_STATUS -> R.string.capability_remote_status
        }

/** One task explanation per feature; local settings remain the only editable copies of their controls. */
@get:StringRes
internal val CapabilityFeature.detailRes: Int
    get() =
        when (this) {
            CapabilityFeature.PARTIAL_AUTH -> R.string.settings_allow_partial_auth_hint
            CapabilityFeature.CARD_SAVING -> R.string.capability_saving_detail
            CapabilityFeature.SAVED_CARDS -> R.string.saved_card_intro
            CapabilityFeature.PAYMENT_LINKS -> R.string.settings_links_hint
            CapabilityFeature.GIFT_CARDS -> R.string.settings_gift_cards_hint
            CapabilityFeature.SCANNED_WALLETS -> R.string.capability_wallet_detail
            CapabilityFeature.MEAL_VOUCHERS -> R.string.voucher_configuration
            CapabilityFeature.MEXICAN_PLANS -> R.string.mexican_configuration
            CapabilityFeature.CASHBACK -> R.string.cashback_configuration
            CapabilityFeature.CASHOUT -> R.string.cashout_configuration
            CapabilityFeature.UNREFERENCED_REFUNDS -> R.string.unreferenced_settings_enable_hint
            CapabilityFeature.MOTO -> R.string.settings_allow_moto_hint
            CapabilityFeature.MKE -> R.string.settings_allow_mke_hint
            CapabilityFeature.DCC -> R.string.capability_dcc_enable_hint
            CapabilityFeature.SURCHARGE -> R.string.capability_surcharge_detail
            CapabilityFeature.TIPPING -> R.string.settings_terminal_tip_hint
            CapabilityFeature.RECEIPT_TIPPING -> R.string.settings_tip_default_hint
            CapabilityFeature.GIVING -> R.string.settings_ask_giving_hint
            CapabilityFeature.AUDIO_GUIDANCE -> R.string.capability_audio_detail
            CapabilityFeature.SHOPPER_DISPLAY -> R.string.settings_shopper_display_hint
            CapabilityFeature.PRINTING -> R.string.settings_auto_print_hint
            CapabilityFeature.SCANNER -> R.string.capability_scanner_detail
            CapabilityFeature.OFFLINE -> R.string.capability_offline_detail
            CapabilityFeature.RECONCILIATION -> R.string.capability_reconciliation_detail
            CapabilityFeature.ABORT -> R.string.capability_abort_detail
            CapabilityFeature.DIAGNOSIS -> R.string.capability_diagnosis_detail
            CapabilityFeature.REMOTE_STATUS -> R.string.capability_late_reply_hint
        }

/** Stable UI identifiers, retaining the original catalog's test identifiers where names differed. */
internal val CapabilityFeature.tag: String
    get() =
        if (this == CapabilityFeature.SCANNER) "cap_terminal_scanner" else "cap_${name.lowercase()}"

/** Setup instructions are separate from status names and name the next task. */
@get:StringRes
internal val CapabilityRequirement.textRes: Int
    get() =
        when (this) {
            CapabilityRequirement.NONE -> R.string.capability_ready_note
            CapabilityRequirement.LOCAL -> R.string.capability_need_local
            CapabilityRequirement.LOCAL_ONLY -> R.string.capability_need_turn_on
            CapabilityRequirement.ADYEN -> R.string.capability_need_adyen
            CapabilityRequirement.REFRESH -> R.string.capability_need_refresh
            CapabilityRequirement.STORE -> R.string.capability_need_store
            CapabilityRequirement.REFERENCE -> R.string.capability_need_reference
            CapabilityRequirement.PROVIDER -> R.string.capability_need_provider
            CapabilityRequirement.PRINTER -> R.string.capability_need_printer
            CapabilityRequirement.SUPPORT -> R.string.capability_need_support
            CapabilityRequirement.UNSUPPORTED -> R.string.capability_unsupported_note
        }
