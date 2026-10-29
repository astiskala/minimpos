package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.minimpos.app.R
import app.minimpos.app.data.settings.CashbackCard
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.app.feature.giftcard.entryLabel
import app.minimpos.app.feature.giftcard.providerName
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.rememberMoneyFormatter
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.shopper.EmailReferenceMode
import app.minimpos.app.data.settings.CashbackSettings as CashbackConfiguration

/** Stores the payment settings with [transform] applied, through [SettingsEvents.onUpdate]. */
internal fun SettingsEvents.onPaymentUpdate(transform: (PaymentSettings) -> PaymentSettings) =
    onUpdate { it.copy(payment = transform(it.payment)) }

/**
 * Currency, payment references, card-entry offers, card saving, link expiry, gift cards and audio guidance; no
 * connection setup or receipt delivery.
 */
@Composable
internal fun ColumnScope.PaymentsSection(
    state: SettingsUiState,
    capabilities: DestinationCapabilities,
    events: SettingsEvents,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val payment = state.settings.payment
    val update = events::onPaymentUpdate
    SectionHeader(stringResource(R.string.settings_pricing), Modifier.testTag("pricingHeader"))
    CurrencySetting(
        selectedCode = payment.currencyCode,
        automatic = PaymentSettings().resolvedCurrency(LocalAppContainer.current.device.country),
        onSelect = { code -> update { it.copy(currencyCode = code) } },
    )
    content()
    SectionHeader(stringResource(R.string.settings_references))
    SettingTextField(
        stringResource(R.string.settings_reference_prefix),
        payment.referencePrefix,
        { value -> update { it.copy(referencePrefix = value.trim()) } },
        description = stringResource(R.string.settings_reference_prefix_hint),
        autoCorrect = false,
    )
    SettingSwitch(stringResource(R.string.settings_ask_transaction_reference), payment.askTransactionReference, { value ->
        update { it.copy(askTransactionReference = value) }
    }, subtitle = stringResource(R.string.settings_ask_transaction_reference_hint))
    SectionHeader(stringResource(R.string.settings_card_payments))
    SettingSwitch(
        stringResource(R.string.settings_allow_partial_auth),
        payment.allowPartialAuthorisation,
        { value -> update { it.copy(allowPartialAuthorisation = value) } },
        subtitle = stringResource(R.string.settings_allow_partial_auth_hint),
        tag = "allowPartialAuthorisation",
    )
    SettingSwitch(
        stringResource(R.string.settings_allow_moto),
        payment.allowMoto,
        { value -> update { it.copy(allowMoto = value) } },
        subtitle = stringResource(R.string.settings_allow_moto_hint),
        tag = "allowMoto",
        blocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { capabilities.supportsTerminalFeatures },
    )
    SettingSwitch(
        stringResource(R.string.settings_allow_mke),
        payment.allowMke,
        { value -> update { it.copy(allowMke = value) } },
        subtitle = stringResource(R.string.settings_allow_mke_hint),
        tag = "allowMke",
        blocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { capabilities.supportsTerminalFeatures },
    )
    MarketSettings(payment, update)
    CashbackSettings(payment, update)
    UnreferencedRefundSection(payment, update)
    SectionHeader(stringResource(R.string.settings_tokenization))
    ShopperReferenceSettings(payment, update)
    SectionHeader(stringResource(R.string.settings_payment_links))
    PaymentLinkSettings(payment, update)
    SectionHeader(stringResource(R.string.settings_gift_cards), Modifier.testTag("giftCardsHeader"))
    GiftCardSettings(payment, update)
    AccessibilitySettings(payment, capabilities, update)
}

/**
 * Audio guidance, which checkout offers per shopper once the merchant confirmed Support enablement, software and
 * terminal language; destinations without terminal features keep the saved choice but cannot change it.
 */
@Composable
private fun ColumnScope.AccessibilitySettings(
    payment: PaymentSettings,
    capabilities: DestinationCapabilities,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_accessibility))
    SettingSwitch(
        stringResource(R.string.settings_audio_guidance),
        payment.audioGuidance,
        { value -> update { it.copy(audioGuidance = value) } },
        subtitle = stringResource(R.string.settings_audio_guidance_hint),
        tag = "audioGuidanceSetting",
        blocked = stringResource(R.string.settings_unavailable_tap_to_pay).takeUnless { capabilities.supportsTerminalFeatures },
    )
}

/**
 * Market-specific card workflows the merchant confirmed with Adyen: Mexican installments and meal vouchers, whose market
 * itself comes from the verified store; Brazilian voucher selection is asked only once vouchers are on.
 */
@Composable
private fun ColumnScope.MarketSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.mexican_title),
        payment.mexicanInstallments,
        { value -> update { it.copy(mexicanInstallments = value) } },
        subtitle = stringResource(R.string.mexican_configuration),
        tag = "mexicanInstallments",
    )
    SettingSwitch(
        stringResource(R.string.voucher_title),
        payment.mealVouchers,
        { value -> update { it.copy(mealVouchers = value) } },
        subtitle = stringResource(R.string.voucher_configuration),
        tag = "mealVouchers",
    )
    if (payment.mealVouchers) {
        SettingSwitch(
            stringResource(R.string.voucher_account_title),
            payment.mealVoucherAccountSelection,
            { value -> update { it.copy(mealVoucherAccountSelection = value) } },
            subtitle = stringResource(R.string.voucher_account_description),
            tag = "mealVoucherAccountSelection",
        )
    }
}

/**
 * The gift-card providers the merchant completed onboarding with at Adyen, each with its card type where Adyen needs
 * one and how staff may present its cards; only what a provider supports is offered. Shared merchant configuration, so
 * it stays editable on a destination without gift cards, whose Gift cards screen explains that instead.
 */
@Composable
private fun ColumnScope.GiftCardSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingNote(stringResource(R.string.settings_gift_cards_hint))
    GiftCardProvider.entries.forEach { provider ->
        val setting = payment.giftCards.firstOrNull { it.provider == provider }

        fun change(transform: (GiftCardSetting) -> GiftCardSetting?) =
            update { current ->
                val existing = current.giftCards.firstOrNull { it.provider == provider } ?: GiftCardSetting(provider)
                current.copy(giftCards = current.giftCards.filterNot { it.provider == provider } + listOfNotNull(transform(existing)))
            }
        SettingSwitch(
            providerName(provider),
            setting != null,
            { on -> change { if (on) it else null } },
            subtitle = stringResource(providerHint(provider)),
            tag = "giftCard_${provider.name}",
        )
        if (setting == null) return@forEach
        if (provider.configuresCode) {
            SettingTextField(
                stringResource(R.string.settings_gift_card_code),
                setting.code,
                { value -> change { it.copy(code = value.trim().lowercase()) } },
                description = stringResource(R.string.settings_gift_card_code_hint),
                autoCorrect = false,
            )
        }
        GiftCardEntry.entries.forEach { entry ->
            SettingSwitch(
                stringResource(entryLabel(entry)),
                entry in setting.entries,
                { on -> change { it.copy(entries = if (on) it.entries + entry else it.entries - entry) } },
                subtitle = stringResource(entryHint(entry)),
                tag = "giftCard_${provider.name}_${entry.name}",
            )
        }
        if (!setting.complete) Notice(stringResource(R.string.settings_gift_card_incomplete), kind = NoticeKind.WARNING, inset = true)
    }
}

/** How staff present a card by [entry], in one line. */
private fun entryHint(entry: GiftCardEntry): Int =
    when (entry) {
        GiftCardEntry.SCANNED -> R.string.settings_gift_card_scanned_hint
        GiftCardEntry.SWIPED -> R.string.settings_gift_card_swiped_hint
        GiftCardEntry.KEYED -> R.string.settings_gift_card_keyed_hint
    }

/** What Adyen documents for [provider], in one line. */
private fun providerHint(provider: GiftCardProvider): Int =
    when (provider) {
        GiftCardProvider.GIVEX -> R.string.settings_gift_card_givex
        GiftCardProvider.LOCAL -> R.string.settings_gift_card_local
        GiftCardProvider.SVS, GiftCardProvider.FISERV, GiftCardProvider.INTERSOLVE -> R.string.settings_gift_card_standard
    }

/** How long payment links remain payable; API readiness belongs to Terminal settings. */
@Composable
private fun ColumnScope.PaymentLinkSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingNote(stringResource(R.string.settings_links_hint))
    SettingNumberField(
        stringResource(R.string.settings_link_expiry),
        payment.linkExpiryHours,
        PaymentSettings.LINK_EXPIRY_HOURS,
        { hours -> update { it.copy(linkExpiryHours = hours) } },
        supporting = stringResource(R.string.settings_link_expiry_hint),
        tag = "linkExpiry",
    )
}

/** Shopper reference source, email hashing and the dependent card-saving options. */
@Composable
private fun ColumnScope.ShopperReferenceSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val source = payment.shopperReferenceSource
    ShopperReferenceSourceChoice(source) { choice -> update { it.copy(shopperReferenceSource = choice) } }
    if (source == ShopperReferenceSource.NONE) return
    if (source == ShopperReferenceSource.EMAIL) EmailReferenceSettings(payment, update)
    CardSavingSettings(payment, update)
}

/** How the shopper's email becomes the shopper reference: hashed (with its salt) or the raw address. */
@Composable
private fun ColumnScope.EmailReferenceSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingChoice(
        title = stringResource(R.string.settings_email_reference_mode),
        options =
            listOf(
                EmailReferenceMode.HASHED to stringResource(R.string.settings_email_hashed),
                EmailReferenceMode.RAW to stringResource(R.string.settings_email_raw),
            ),
        selected = payment.emailReferenceMode,
        onSelect = { mode -> update { it.copy(emailReferenceMode = mode) } },
        subtitle = stringResource(R.string.settings_email_reference_hint),
    )
    if (payment.emailReferenceMode == EmailReferenceMode.HASHED) {
        SettingTextField(
            stringResource(R.string.settings_email_salt),
            payment.emailReferenceSalt,
            { value -> update { it.copy(emailReferenceSalt = value) } },
            supporting = stringResource(R.string.settings_email_salt_hint),
        )
    }
}

/** Whether checkout offers card saving and its initial selections. */
@Composable
private fun ColumnScope.CardSavingSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.settings_offer_card_saving),
        payment.offerCardSaving,
        { value -> update { it.copy(offerCardSaving = value) } },
        subtitle = stringResource(R.string.settings_offer_card_saving_hint),
        tag = "offerCardSaving",
    )
    if (!payment.offerCardSaving) return
    SaveCardDefaults(payment, update)
}

/** What supplies the shopper reference; checkout asks for a customer reference only when selected here. */
@Composable
private fun ShopperReferenceSourceChoice(
    source: ShopperReferenceSource,
    onSelect: (ShopperReferenceSource) -> Unit,
) = SettingChoice(
    title = stringResource(R.string.settings_shopper_reference_source),
    options =
        listOf(
            ShopperReferenceSource.NONE to stringResource(R.string.settings_source_none),
            ShopperReferenceSource.CUSTOMER_REFERENCE to stringResource(R.string.settings_source_customer),
            ShopperReferenceSource.EMAIL to stringResource(R.string.settings_source_email),
        ),
    selected = source,
    onSelect = onSelect,
    subtitle =
        stringResource(
            when (source) {
                ShopperReferenceSource.NONE -> R.string.settings_source_none_hint
                ShopperReferenceSource.CUSTOMER_REFERENCE -> R.string.settings_source_customer_hint
                ShopperReferenceSource.EMAIL -> R.string.settings_source_email_hint
            },
        ),
    tag = "referenceSource",
)

/** Whether "Save card" starts switched on at checkout, for sales and for pre-authorisations. */
@Composable
private fun ColumnScope.SaveCardDefaults(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.settings_tokenize_default),
        payment.tokenizeDefaultOn,
        { value -> update { it.copy(tokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_tokenize_default_hint),
        tag = "tokenizeDefault",
    )
    SettingSwitch(
        stringResource(R.string.settings_pre_auth_tokenize_default),
        payment.preAuthTokenizeDefaultOn,
        { value -> update { it.copy(preAuthTokenizeDefaultOn = value) } },
        subtitle = stringResource(R.string.settings_pre_auth_tokenize_default_hint),
        tag = "preAuthTokenizeDefault",
    )
}

/** Explicit Support/card enablement and local cash limit, separate for purchase-linked and standalone requests. */
@Composable
private fun ColumnScope.CashbackSettings(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val cash = payment.cashback
    val container = LocalAppContainer.current
    val verified by container.walletDiscovery.state.collectAsStateWithLifecycle()
    val currency = container.currency().code
    val money = rememberMoneyFormatter(currency)
    val change: ((CashbackConfiguration) -> CashbackConfiguration) -> Unit = { transform ->
        update { it.copy(cashback = transform(it.cashback)) }
    }
    val confirm: (CashbackConfiguration) -> CashbackConfiguration = { value ->
        value.confirmedFor(verified.context, verified.storeId, verified.storeCountry, currency)
    }
    SectionHeader(stringResource(R.string.cashback_title))
    SettingSwitch(
        stringResource(R.string.cashback_enable),
        cash.enabled,
        { value -> change { if (value) confirm(it).copy(enabled = true) else it.copy(enabled = false) } },
        subtitle = stringResource(R.string.cashback_configuration),
    )
    SettingSwitch(
        stringResource(R.string.cashout_enable),
        cash.standalone,
        { value -> change { if (value) confirm(it).copy(standalone = true) else it.copy(standalone = false) } },
        subtitle = stringResource(R.string.cashout_configuration),
    )
    CashbackCard.entries.forEach { card ->
        SettingSwitch(
            card.name,
            cash.card == card,
            { checked -> change { it.selecting(card.takeIf { checked }) } },
            subtitle = stringResource(R.string.cashback_card_hint),
        )
    }
    SettingTextField(
        stringResource(R.string.cashback_limit),
        money.formatPlain(cash.limitMinor),
        { value -> change { it.copy(limitMinor = CurrencySpec.of(currency).parseMinor(value) ?: 0) } },
        description = stringResource(R.string.cashback_limit_hint),
    )
}

/**
 * Explicit Adyen enablement, local limit and refund delay of unreferenced refunds, bound to the verified store when
 * turned on. The country rule and Adyen's own limit are explained here, never configured.
 */
@Composable
private fun ColumnScope.UnreferencedRefundSection(
    payment: PaymentSettings,
    update: ((PaymentSettings) -> PaymentSettings) -> Unit,
) {
    val refunds = payment.unreferencedRefunds
    val container = LocalAppContainer.current
    val verified by container.walletDiscovery.state.collectAsStateWithLifecycle()
    val currency = container.currency().code
    val money = rememberMoneyFormatter(currency)
    val change: ((UnreferencedRefundSettings) -> UnreferencedRefundSettings) -> Unit = { transform ->
        update { it.copy(unreferencedRefunds = transform(it.unreferencedRefunds)) }
    }
    val confirm: (UnreferencedRefundSettings) -> UnreferencedRefundSettings = { value ->
        value.confirmedFor(verified.context, verified.storeId, verified.storeCountry, currency)
    }
    SectionHeader(stringResource(R.string.unreferenced_settings_title))
    SettingSwitch(
        stringResource(R.string.unreferenced_settings_enable),
        refunds.enabled,
        { value -> change { if (value) confirm(it).copy(enabled = true) else it.copy(enabled = false) } },
        subtitle = stringResource(R.string.unreferenced_settings_enable_hint),
        tag = "unreferencedEnable",
    )
    SettingTextField(
        stringResource(R.string.unreferenced_settings_limit),
        money.formatPlain(refunds.limitMinor),
        { value -> change { it.copy(limitMinor = CurrencySpec.of(currency).parseMinor(value) ?: 0) } },
        description = stringResource(R.string.unreferenced_settings_limit_hint),
    )
    SettingSwitch(
        stringResource(R.string.unreferenced_settings_delay),
        refunds.delayed,
        { value -> change { if (value) confirm(it).copy(delayed = true) else it.copy(delayed = false) } },
        subtitle = stringResource(R.string.unreferenced_settings_delay_hint),
        tag = "unreferencedDelay",
    )
}
