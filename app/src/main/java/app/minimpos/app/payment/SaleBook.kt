package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.TenderBalance
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.CardFeature
import app.minimpos.core.payment.CardFeatures
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.split.AllocatableLine
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.core.voucher.VoucherSelection
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.GiftCardPayment
import app.minimpos.terminal.client.GiftCardUse
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.ScannedPayment
import app.minimpos.terminal.client.ShopperSessionFlow
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TransactionKind

/**
 * Everything checkout collected for one payment, as [Checkout.paymentStart] makes it.
 *
 * @property totals The priced cart; its gross total is the amount charged.
 * @property currency The currency charged.
 * @property merchantReference Adyen's merchant reference for the payment, such as `260930-145811-VQ45`.
 * @property customerReference Sent as payment metadata when set; only when it is also the shopper reference.
 * @property shopperEmail The shopper's email collected before payment, sent to Adyen and used for the receipt; null if absent.
 * @property tokenization Set only when the shopper opted in to saving their card (under [shopperReference]).
 * @property kind A sale, or a pre-authorisation that only holds the amount (sent with `authorisationType=PreAuth` and
 *   manual capture).
 * @property tipOnReceipt For a sale: taken for tipping on the receipt, so it is pre-authorised like a pre-authorisation
 *   and captured with the tip once that is entered (see [Captures]).
 * @property shopperReference Adyen's `shopperReference`, sent with the payment whether or not the card is saved; null
 *   for none.
 * @property sessionRevision Originating in-memory session revision; null when not started through a session.
 * @property scannedPayment Single-use, transient scanned instrument; null for ordinary terminal payments.
 * @property walletContext Verified scan or Mexican-store destination; persisted before sending.
 * @property walletSetupIdentity Fingerprint of checked connection/secrets for a scan or Mexican store; never a credential.
 * @property terminalTip Native terminal tipping options (`AskGratuity` and optional `SaleToPOIData`); null when disabled.
 * @property shopperFlow Chained customer-display and input prompts run before and after payment; null when disabled.
 * @property allowPartialAuthorisation Whether `AllowPartialAuthorisation` is sent on this tender.
 * @property requestedAmountMinor Amount requested for this tender in minor units; defaults to `totals.amounts.gross`.
 * @property existingSaleId Existing partially paid sale ID when collecting the remaining balance; null for a new sale.
 * @property askGiving Whether `AskGiving` is sent on this payment.
 * @property audioGuidance Whether audio guidance was selected and explicitly confirmed for this shopper.
 * @property mexicanPayment Frozen Mexican card routing and monthly count; null for ordinary routing.
 * @property audioGuidancePoiId Confirmed terminal POIID; checked again immediately before sending.
 * @property cardEntryOverride Card entry override (`NORMAL`, `MOTO` or `MKE`).
 * @property allocation Equal-share or item allocation this tender pays; null for an amount or the whole balance.
 * @property reviewedPaidMinor Purchase already paid when an [existingSaleId] tender was reviewed; a changed stored
 *   amount refuses the start as stale.
 * @property giftCard Transient gift card this tender redeems (a split tender of an immediate sale); null for cards.
 * @property mealVoucher Meal-voucher market this tender pays from, frozen with its eligible amount and the verified
 *   store's [walletContext]; null for other tenders.
 * @property cashBackMinor Cash requested in addition to the purchase, minor units; null for no cash.
 * @property cashbackConfiguration Exact non-secret enablement the operator confirmed; checked again before sending.
 * @throws IllegalArgumentException if two of its card features cannot be combined ([CardFeatures.conflict]), or a
 *   feature lacks what it needs, such as its verified store or a [shopperReference] to save the card under.
 */
data class PaymentStart(
    val totals: CartTotals,
    val currency: CurrencySpec,
    val merchantReference: String,
    val customerReference: String?,
    val shopperEmail: String?,
    val tokenization: TokenizationRequest?,
    val kind: SaleKind = SaleKind.SALE,
    val tipOnReceipt: Boolean = false,
    val shopperReference: String? = null,
    val sessionRevision: Long? = null,
    val scannedPayment: ScannedPayment? = null,
    val walletContext: PaymentContext? = null,
    val walletSetupIdentity: String? = null,
    val terminalTip: TerminalTipOptions? = null,
    val shopperFlow: ShopperSessionFlow? = null,
    val allowPartialAuthorisation: Boolean = false,
    val requestedAmountMinor: Long = totals.amounts.gross,
    val existingSaleId: String? = null,
    val askGiving: Boolean = false,
    val audioGuidance: Boolean = false,
    val mexicanPayment: MexicanPayment? = null,
    val audioGuidancePoiId: String? = null,
    val cardEntryOverride: CardEntryOverride = CardEntryOverride.NORMAL,
    val allocation: TenderAllocation? = null,
    val reviewedPaidMinor: Long = 0,
    val giftCard: PresentedGiftCard? = null,
    val mealVoucher: MealVoucherScheme? = null,
    val cashBackMinor: Long? = null,
    val cashbackConfiguration: CashbackSettings? = null,
) {
    /** The card features this payment asks for, which [CardFeatures] relates; checked for conflicts on construction. */
    internal val cardFeatures: Set<CardFeature>
        get() =
            buildSet {
                if (kind == SaleKind.PRE_AUTHORISATION) add(CardFeature.PRE_AUTHORISATION)
                if (tipOnReceipt) add(CardFeature.TIP_ON_RECEIPT)
                if (terminalTip != null) add(CardFeature.TERMINAL_TIP)
                if (shopperFlow != null) add(CardFeature.SHOPPER_DISPLAY)
                if (askGiving) add(CardFeature.GIVING)
                if (audioGuidance) add(CardFeature.AUDIO_GUIDANCE)
                if (allowPartialAuthorisation) add(CardFeature.PARTIAL_AUTHORISATION)
                if (tokenization != null) add(CardFeature.TOKENIZATION)
                if (cardEntryOverride != CardEntryOverride.NORMAL) add(CardFeature.SPECIAL_ENTRY)
                if (scannedPayment != null) add(CardFeature.SCANNED_WALLET)
                if (giftCard != null) add(CardFeature.GIFT_CARD)
                if (mealVoucher != null) add(CardFeature.MEAL_VOUCHER)
                if (mexicanPayment != null) add(CardFeature.MEXICAN_PLAN)
                if (cashBackMinor != null) add(CardFeature.CASHBACK)
                if (allocation != null) add(CardFeature.ALLOCATION)
                if (existingSaleId != null) add(CardFeature.FURTHER_TENDER)
            }

    init {
        require(CardFeatures.conflict(cardFeatures) == null) { "Card features $cardFeatures include two that cannot be combined" }
        require(cardFeatures.none { it.needsVerifiedStore } || (walletContext != null && walletSetupIdentity != null)) {
            "Scanned wallets, meal vouchers, Mexican routing and cash need their verified store"
        }
        require(
            cashBackMinor == null ||
                (
                    cashBackMinor > 0 && cashbackConfiguration != null &&
                        requestedAmountMinor == Math.addExact(totals.amounts.gross, cashBackMinor)
                ),
        ) { "Cash is requested in addition to the whole purchase, under a confirmed configuration" }
        require(
            mealVoucher == null || (currency.code == mealVoucher.currency && allowPartialAuthorisation == mealVoucher.partialAuthorisation),
        ) {
            "A meal voucher pays in its market's currency, with partial approval only where the market documents it"
        }
        require(mexicanPayment == null || (currency.code == "MXN" && requestedAmountMinor == totals.amounts.gross)) {
            "Mexican routing pays a whole sale in MXN"
        }
        require(giftCard == null || giftCard.setting.provider.supports(GiftCardOperation.REDEEM)) { "The gift card provider redeems" }
        require(allocation !is TenderAllocation.Items || !allowPartialAuthorisation) {
            "Item tenders are collected in full so their units are paid exactly once"
        }
        require(!audioGuidance || audioGuidancePoiId != null) { "Audio guidance requires a confirmed terminal" }
        require(tokenization == null || shopperReference != null) { "Saving a card needs a shopper reference" }
    }

    /**
     * Whether the payment only holds its amount until it is captured (`authorisationType=PreAuth` with manual capture):
     * a pre-authorisation, or a sale taken for tipping on the receipt, which Adyen's flow captures with the tip.
     */
    val manualCapture: Boolean get() = kind == SaleKind.PRE_AUTHORISATION || tipOnReceipt

    /**
     * This payment as a saved-card charge, without what only a terminal offers: saving the card, partial approval,
     * split tender, giving, receipt or terminal tipping, shopper prompts and special card-entry overrides.
     */
    fun forSavedCard(): PaymentStart =
        copy(
            tokenization = null,
            audioGuidance = false,
            audioGuidancePoiId = null,
            tipOnReceipt = false,
            terminalTip = null,
            shopperFlow = null,
            allowPartialAuthorisation = false,
            requestedAmountMinor = totals.amounts.gross,
            askGiving = false,
            cardEntryOverride = CardEntryOverride.NORMAL,
            allocation = null,
        )

    /** What this payment requests on its tender, in [currency]. */
    val amount: OperationAmount get() = OperationAmount(requestedAmountMinor, currency.code)

    /** This tender paid with [card] instead of a payment card: no card saving or special entry; a short balance may be approved. */
    fun withGiftCard(card: PresentedGiftCard): PaymentStart =
        copy(
            giftCard = card,
            tokenization = null,
            cardEntryOverride = CardEntryOverride.NORMAL,
            // Adyen asks gift-card payments to allow a short balance; item tenders are still collected in full.
            allowPartialAuthorisation = allocation !is TenderAllocation.Items,
        )

    /**
     * This amount tender paid from a [scheme] meal voucher on the verified store [context] with exact [setupIdentity]:
     * no card saving, prompts or special entry, and a short balance approved only where the market documents it.
     */
    internal fun withMealVoucher(
        scheme: MealVoucherScheme,
        context: PaymentContext,
        setupIdentity: String,
    ): PaymentStart =
        copy(
            mealVoucher = scheme,
            tokenization = null,
            terminalTip = null,
            shopperFlow = null,
            askGiving = false,
            cardEntryOverride = CardEntryOverride.NORMAL,
            allowPartialAuthorisation = scheme.partialAuthorisation,
            walletContext = context,
            walletSetupIdentity = setupIdentity,
        )

    /** Attaches a transient [instrument] only to this validated sale, bound to [context] and exact [setupIdentity]. */
    internal fun withScanned(
        instrument: ScannedPayment,
        context: PaymentContext,
        setupIdentity: String,
    ): PaymentStart = copy(scannedPayment = instrument, walletContext = context, walletSetupIdentity = setupIdentity)
}

/**
 * Saving the shopper's card with the payment (Adyen tokenization), under the payment's
 * [PaymentStart.shopperReference].
 *
 * @property recurringProcessingModel How the saved card will be used later.
 */
data class TokenizationRequest(
    val recurringProcessingModel: RecurringModel,
)

/**
 * Card payments as stored sales ([SaleEntity] with its [SaleLineEntity] items), for the payments' [TransactionLifecycle].
 * The sale gets the cart as priced, the terminal's POIID once the request is sent, and the card, receipt and
 * tokenization details of the answer (and, for a pre-authorised payment, the blob for synchronous adjustments).
 *
 * @param sales Where sales are stored.
 */
class SaleBook(
    private val sales: SaleRepository,
) : TransactionBook<PaymentStart> {
    override val kind: TransactionKind = TransactionKind.PAYMENT

    override fun existingId(request: PaymentStart): String? = request.existingSaleId

    override suspend fun open(
        id: String,
        request: PaymentStart,
        serviceId: String,
        createdAt: Long,
    ): Boolean {
        val existing = request.existingSaleId
        if (existing != null) {
            val opened = SaleEvent.TenderOpened(serviceId, request.requestedAmountMinor, request.reviewedPaidMinor)
            return sales.openTender(
                existing,
                opened,
                request.allocation,
                createdAt,
                request.giftCard
                    ?.setting
                    ?.provider
                    ?.name,
                request.mealVoucher,
            )
        }
        val split =
            request.cashBackMinor == null &&
                (request.requestedAmountMinor != request.totals.amounts.gross || request.allocation != null)
        val voucher = request.mealVoucher
        if (voucher != null && !splitBalance(request).acceptsVoucher(request.requestedAmountMinor, voucher)) return false
        if (split && !splitBalance(request).accepts(request.requestedAmountMinor, request.allocation)) return false
        val tender = SaleRepository.openedTender(id, 1, request.requestedAmountMinor, request.allocation, createdAt)
        sales.createPending(
            pendingSale(id, request, createdAt).copy(serviceId = serviceId),
            lines(id, request.totals),
            tender.copy(
                serviceId = serviceId,
                mexicanPayment = request.mexicanPayment?.name,
                requestedCashBackMinor = request.cashBackMinor,
                mealVoucher = request.mealVoucher?.name,
                giftCard =
                    request.giftCard
                        ?.setting
                        ?.provider
                        ?.name,
            ),
        )
        return true
    }

    override suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) {
        sales.record(id, SaleEvent.ContextRecorded(context))
    }

    override suspend fun context(id: String) = sales.get(id)?.sale?.context

    override fun operation(request: PaymentStart): TerminalOperation {
        val tokenization = request.tokenization
        val isSplit = request.existingSaleId != null || request.requestedAmountMinor < request.totals.amounts.gross
        return TerminalOperation.Pay(
            collectionAction = if (request.kind == SaleKind.PRE_AUTHORISATION) ManagerAction.PRE_AUTHORISATION else ManagerAction.SALE,
            params =
                PaymentParams(
                    amountMinor = request.requestedAmountMinor,
                    currency = request.currency.code,
                    merchantReference = request.merchantReference,
                    shopperReference = request.shopperReference,
                    shopperEmail = request.shopperEmail,
                    recurringProcessingModel = tokenization?.recurringProcessingModel?.takeIf { request.giftCard == null },
                    // The app prints its own combined receipt, including the card details.
                    appPrintsReceipts = true,
                    metadata = listOfNotNull(request.customerReference?.let { "customerReference" to it }).toMap(),
                    requestCardAlias = tokenization != null && request.scannedPayment == null && request.giftCard == null,
                    // A tip on the receipt is captured later with the tip, as Adyen's tipping on the receipt flow asks.
                    preAuthorisation = request.manualCapture,
                    scannedPayment = request.scannedPayment,
                    terminalTip = request.terminalTip,
                    audioGuidance = request.audioGuidance,
                    shopperFlow = request.shopperFlow,
                    allowPartialAuthorisation = request.allowPartialAuthorisation,
                    saleReferenceId = request.merchantReference.takeIf { isSplit || request.allowPartialAuthorisation },
                    askGiving = request.askGiving,
                    cardEntryOverride = request.cardEntryOverride,
                    mexicanPayment = request.mexicanPayment,
                    giftCard = request.giftCard?.let { GiftCardPayment(it.account, GiftCardUse.REDEEM) },
                    mealVoucherAccount = request.mealVoucher?.selection == VoucherSelection.ACCOUNT_TYPE,
                    cashBackMinor = request.cashBackMinor,
                ),
            cashbackIdentity = request.cashbackConfiguration?.identity,
            audioGuidancePoiId = request.audioGuidancePoiId,
            expected = request.walletContext,
            setupIdentity = request.walletSetupIdentity,
        )
    }

    override fun release(request: PaymentStart) {
        request.scannedPayment?.discard()
        request.giftCard?.discard()
    }

    override suspend fun sending(
        id: String,
        poiId: String,
    ) {
        sales.record(id, SaleEvent.Sending(poiId))
    }

    override suspend fun settle(
        id: String,
        settlement: Settlement,
    ): Boolean {
        val status =
            when (settlement.status) {
                SettlementStatus.SUCCEEDED -> SaleStatus.APPROVED
                SettlementStatus.CANCELLED -> SaleStatus.CANCELLED
                SettlementStatus.DECLINED -> SaleStatus.DECLINED
                SettlementStatus.FAILED -> SaleStatus.FAILED
                SettlementStatus.UNKNOWN -> SaleStatus.UNKNOWN
            }
        return sales.record(
            id,
            SaleEvent.Settled(
                status,
                settlement.message,
                settlement.details,
                settlement.reason,
                busyServiceId = settlement.details?.decline?.busyServiceId,
            ),
        )
    }

    override suspend fun abortBusy(
        id: String,
        abort: suspend (serviceId: String) -> Boolean,
    ): Boolean {
        val serviceId = sales.get(id)?.sale?.busyServiceId ?: return false
        if (!abort(serviceId)) return false
        sales.record(id, SaleEvent.BusyAborted)
        return true
    }

    override suspend fun unsettledServiceId(id: String): String? =
        sales
            .get(id)
            ?.sale
            ?.takeIf { it.status == SaleStatus.UNKNOWN }
            ?.serviceId

    /** Terminal API values and conversions shared with refunds, and the stored form of a payment, shared with links. */
    companion object {
        /** How a new sale's frozen cart is divided before anything is paid, its lines keyed by cart position. */
        fun splitBalance(request: PaymentStart): TenderBalance = splitBalance(request.totals, request.manualCapture)

        /** How the priced cart [totals] would be divided before anything is paid; no items for a [manualCapture]. */
        fun splitBalance(
            totals: CartTotals,
            manualCapture: Boolean,
        ): TenderBalance =
            TenderBalance(
                totals.amounts.gross,
                0,
                totals.lines.mapIndexed { index, priced -> AllocatableLine(index.toLong(), priced.line.quantity, 0, priced.amounts.gross) },
                emptyList(),
                itemsAllowed = !manualCapture,
                eligibleMinor =
                    totals.lines.filter { it.line.mealVoucherEligible }.fold(
                        0L,
                    ) { sum, priced -> Math.addExact(sum, priced.amounts.gross) },
            )

        /** [request] as the new PENDING sale [id], started at [createdAt] (epoch ms), before anything is sent. */
        internal fun pendingSale(
            id: String,
            request: PaymentStart,
            createdAt: Long,
        ): SaleEntity {
            val totals = request.totals
            return SaleEntity(
                id = id,
                createdAt = createdAt,
                currency = request.currency.code,
                taxMode = totals.mode.name,
                netMinor = totals.amounts.net,
                taxMinor = totals.amounts.tax,
                totalMinor = totals.amounts.gross,
                status = SaleStatus.PENDING,
                merchantReference = request.merchantReference,
                customerReference = request.customerReference,
                shopperReference = request.shopperReference,
                shopperEmail = request.shopperEmail,
                tokenizationRequested = request.tokenization != null,
                requestedWallet = request.scannedPayment?.brand,
                context = request.walletContext,
                kind = request.kind,
                tipOnReceipt = request.tipOnReceipt,
                audioGuidance = request.audioGuidance,
                tenderRequestedMinor = request.requestedAmountMinor,
                requestedCashBackMinor = request.cashBackMinor,
            )
        }

        /**
         * [start] as the new PENDING saved-card sale [id] in the original [context], opened at [createdAt] (epoch ms),
         * with the card and recurring model it charges, before anything is sent.
         */
        internal fun savedCardSale(
            id: String,
            start: SavedCardStart,
            createdAt: Long,
            context: PaymentContext?,
        ): SaleEntity =
            pendingSale(id, start.payment, createdAt).copy(
                savedCard = true,
                chargedCardId = start.card.id,
                chargeRecurringModel = start.recurringModel,
                paymentBrand = start.card.brand,
                maskedPan = start.card.lastFour?.let { "**** $it" },
                context = context,
            )

        /** The priced cart [totals] as the lines of sale [saleId], in cart order. */
        internal fun lines(
            saleId: String,
            totals: CartTotals,
        ): List<SaleLineEntity> =
            totals.lines.mapIndexed { index, priced ->
                val line = priced.line
                SaleLineEntity(
                    saleId = saleId,
                    position = index,
                    productId = line.productId,
                    name = line.name,
                    sku = line.sku,
                    unitPriceMinor = line.unitPrice,
                    quantity = line.quantity,
                    taxName = line.tax.name,
                    taxRateMilliPercent = line.tax.rateMilliPercent,
                    netMinor = priced.amounts.net,
                    taxMinor = priced.amounts.tax,
                    grossMinor = priced.amounts.gross,
                    mealVoucherEligible = line.mealVoucherEligible,
                )
            }
    }
}
