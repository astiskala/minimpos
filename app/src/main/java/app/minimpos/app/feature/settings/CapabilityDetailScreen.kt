package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.InfoDialog
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.ScreenHeader
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.SetupRequired
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens

/** Focused feature setup, with remote drafts separate from links to the original autosaved local settings. */
@Composable
internal fun CapabilityDetailScreen(
    feature: CapabilityFeature,
    settings: AppSettings,
    loaded: Boolean,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    header: ScreenHeader = ScreenHeader.FULL,
) {
    val container = LocalAppContainer.current
    val vm = capabilitiesViewModel()
    val remote by vm.state.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val terminal by container.terminalStatus.state.collectAsStateWithLifecycle()
    val wallets by container.walletDiscovery.state.collectAsStateWithLifecycle()
    val currency = container.currency(settings).code
    val item = CapabilityCatalog(settings, terminal, remote, wallets, currency).item(feature)
    val memory = if (feature.editableAtAdyen) rememberCapabilityEdit(feature, remote, actions, currency) else null
    val edit = memory?.value
    CapabilityLeaveReview(navigator, feature, edit?.let { it.dirty || it.unconfirmed } == true, actions.running) {
        if (memory != null) memory.value = memory.value.discard(CapabilityDraft.from(feature, remote, currency))
    }
    MiniScaffold(
        title = stringResource(feature.titleRes),
        onBack = navigator::back,
        modifier = modifier,
        header = header,
    ) { padding ->
        SettingsSectionLayout(padding, loaded) {
            SettingIntro(stringResource(feature.detailRes))
            Column(Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding)) { CapabilityStatusLine(item) }
            if (currentSetupGate().pending) {
                SetupRequired()
            } else {
                CapabilityRequirementNotice(item)
                if (edit != null && item.status != CapabilityStatus.UNSUPPORTED && remote.checked) {
                    CapabilityEditor(
                        feature,
                        remote,
                        actions,
                        currency,
                        edit,
                        { memory.value = it },
                        vm::refresh,
                        vm::dismissResult,
                        { edited -> vm.saveEditor(feature, edited, currency) },
                    )
                } else {
                    CapabilityRefresh(remote, actions.running, vm::refresh)
                    if (feature.editableAtAdyen && item.status != CapabilityStatus.UNSUPPORTED) {
                        SettingNote(stringResource(R.string.capability_need_refresh))
                    }
                }
                if (feature == CapabilityFeature.SCANNED_WALLETS && wallets.supported) WalletOffers(wallets, currency)
                if (item.status != CapabilityStatus.UNSUPPORTED) {
                    CapabilityLocalLinks(feature, settings, navigator)
                }
                SettingNote(stringResource(R.string.capability_ready_note))
            }
        }
    }
}

@Composable
private fun CapabilityRequirementNotice(item: CapabilityItem) {
    if (item.requirement == CapabilityRequirement.NONE) return
    val kind = if (item.status in setOf(CapabilityStatus.NEEDS_SETUP, CapabilityStatus.UNVERIFIED)) NoticeKind.WARNING else NoticeKind.INFO
    Notice(stringResource(item.requirement.textRes), kind = kind, inset = true)
}

/** Reviews Back, system Back and expanded-pane navigation before disposing a dirty remote editor. */
@Composable
private fun CapabilityLeaveReview(
    navigator: Navigator,
    feature: CapabilityFeature,
    dirty: Boolean,
    running: Boolean,
    onDiscard: () -> Unit,
) {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var waiting by remember { mutableStateOf(false) }
    DisposableEffect(navigator, feature, dirty, running) {
        val dispose =
            if (dirty || running) {
                navigator.reviewLeaving(Route.SettingsSection(SettingsSections.CAPABILITIES, feature = feature.name)) { next ->
                    if (running) waiting = true else pending = next
                }
            } else {
                null
            }
        onDispose { dispose?.invoke() }
    }
    if (pending != null) {
        ConfirmDialog(
            title = stringResource(R.string.capability_discard_title),
            message = stringResource(R.string.capability_discard_message),
            confirmLabel = stringResource(R.string.capability_discard),
            dismissLabel = stringResource(R.string.capability_keep_editing),
            destructive = true,
            onConfirm = {
                val next = pending
                pending = null
                onDiscard()
                next?.invoke()
            },
            onDismiss = { pending = null },
        )
    }
    if (waiting) {
        InfoDialog(
            stringResource(R.string.capability_save),
            stringResource(R.string.capability_wait_save),
            "capabilityWait",
        ) { waiting = false }
    }
}

@Composable
private fun ColumnScope.CapabilityLocalLinks(
    feature: CapabilityFeature,
    settings: AppSettings,
    navigator: Navigator,
) {
    val links = feature.localLinks(settings)
    if (links.isNotEmpty()) {
        SectionHeader(stringResource(R.string.capability_local_settings))
        SettingNote(stringResource(R.string.capability_local_autosave))
    }
    links.forEach { (title, section, tag) ->
        SettingNavRow(
            Icons.Default.Settings,
            stringResource(title),
            stringResource(R.string.capability_open_setting),
            { navigator.push(Route.SettingsSection(section, setting = tag)) },
            tag = "openSetting_$tag",
        )
    }
}

/** One original setting's title, section and exact tagged control. */
private data class CapabilityLocalLink(
    val title: Int,
    val section: String,
    val tag: String,
)

private fun CapabilityFeature.localLinks(settings: AppSettings): List<CapabilityLocalLink> {
    if (this == CapabilityFeature.CARD_SAVING) {
        return listOfNotNull(
            CapabilityLocalLink(R.string.settings_shopper_reference_source, SettingsSections.PAYMENTS, "referenceSource"),
            CapabilityLocalLink(R.string.settings_offer_card_saving, SettingsSections.PAYMENTS, "offerCardSaving")
                .takeIf { settings.payment.shopperReferenceSource != ShopperReferenceSource.NONE },
        )
    }
    return capabilityLocalLinks[this].orEmpty()
}

private val capabilityLocalLinks: Map<CapabilityFeature, List<CapabilityLocalLink>> =
    mapOf(
        CapabilityFeature.PARTIAL_AUTH to (R.string.settings_allow_partial_auth to "allowPartialAuthorisation"),
        CapabilityFeature.MOTO to (R.string.settings_allow_moto to "allowMoto"),
        CapabilityFeature.MKE to (R.string.settings_allow_mke to "allowMke"),
        CapabilityFeature.MEAL_VOUCHERS to (R.string.voucher_title to "mealVouchers"),
        CapabilityFeature.MEXICAN_PLANS to (R.string.mexican_title to "mexicanInstallments"),
        CapabilityFeature.CASHBACK to (R.string.cashback_enable to "cashbackEnable"),
        CapabilityFeature.CASHOUT to (R.string.cashout_enable to "cashoutEnable"),
        CapabilityFeature.UNREFERENCED_REFUNDS to (R.string.unreferenced_settings_enable to "unreferencedEnable"),
        CapabilityFeature.AUDIO_GUIDANCE to (R.string.settings_audio_guidance to "audioGuidanceSetting"),
        CapabilityFeature.GIFT_CARDS to (R.string.settings_gift_cards to "giftCardsHeader"),
        CapabilityFeature.PAYMENT_LINKS to (R.string.settings_link_expiry to "linkExpiry"),
        CapabilityFeature.SAVED_CARDS to (R.string.settings_shopper_reference_source to "referenceSource"),
    ).mapValues { (_, setting) -> listOf(CapabilityLocalLink(setting.first, SettingsSections.PAYMENTS, setting.second)) } +
        mapOf(
            CapabilityFeature.TIPPING to (R.string.checkout_tip_on_terminal to "terminalTipping"),
            CapabilityFeature.RECEIPT_TIPPING to (R.string.checkout_tip_on_receipt to "receiptTipping"),
            CapabilityFeature.GIVING to (R.string.checkout_ask_giving to "askGivingSetting"),
        ).mapValues { (_, setting) -> listOf(CapabilityLocalLink(setting.first, SettingsSections.TIPPING, setting.second)) } +
        mapOf(
            CapabilityFeature.PRINTING to
                listOf(
                    CapabilityLocalLink(R.string.settings_printer, SettingsSections.RECEIPTS, "receiptPrinter"),
                    CapabilityLocalLink(R.string.settings_auto_print, SettingsSections.RECEIPTS, "autoPrint"),
                ),
            CapabilityFeature.SHOPPER_DISPLAY to
                listOf(
                    R.string.settings_show_virtual_receipt to "showVirtualReceipt",
                    R.string.settings_confirm_basket_terminal to "confirmBasketOnTerminal",
                    R.string.settings_collect_email_terminal to "collectEmailOnTerminal",
                    R.string.settings_show_terminal_qr to "showTerminalQrReceipt",
                    R.string.settings_ask_shopper_rating to "askShopperRating",
                ).map { (title, tag) -> CapabilityLocalLink(title, SettingsSections.SHOPPER_DISPLAY, tag) },
        )
