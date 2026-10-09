package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.ScannedPayment
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
 * @property walletContext Original scan destination; required with a scanned instrument and persisted before sending.
 * @property walletSetupIdentity Fingerprint of checked connection/secrets; required for scans and never persisted as a credential.
 * @throws IllegalArgumentException if a pre-authorisation is taken for tipping on the receipt, or a card is to be saved
 *   without a [shopperReference].
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
) {
    init {
        require(!tipOnReceipt || kind == SaleKind.SALE) { "Only a sale can be taken for tipping on the receipt" }
        require(tokenization == null || shopperReference != null) { "Saving a card needs a shopper reference" }
        require(
            scannedPayment == null || (kind == SaleKind.SALE && !tipOnReceipt && walletContext != null && walletSetupIdentity != null),
        ) {
            "Scanned payments require an immediate-charge sale and its checked destination"
        }
    }

    /**
     * Whether the payment only holds its amount until it is captured (`authorisationType=PreAuth` with manual capture):
     * a pre-authorisation, or a sale taken for tipping on the receipt, which Adyen's flow captures with the tip.
     */
    val manualCapture: Boolean get() = kind == SaleKind.PRE_AUTHORISATION || tipOnReceipt

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

    override suspend fun open(
        id: String,
        request: PaymentStart,
        serviceId: String,
        createdAt: Long,
    ) {
        sales.createPending(pendingSale(id, request, createdAt).copy(serviceId = serviceId), lines(id, request.totals))
    }

    override suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = sales.record(id, SaleEvent.ContextRecorded(context))

    override suspend fun context(id: String) = sales.get(id)?.sale?.context

    override fun operation(request: PaymentStart): TerminalOperation {
        val tokenization = request.tokenization
        return TerminalOperation.Pay(
            PaymentParams(
                amount = request.currency.toMajor(request.totals.amounts.gross),
                currency = request.currency.code,
                merchantReference = request.merchantReference,
                shopperReference = request.shopperReference,
                shopperEmail = request.shopperEmail,
                recurringProcessingModel = tokenization?.recurringProcessingModel,
                // The app prints its own combined receipt, including the card details.
                tenderOptions = listOf(RECEIPT_HANDLER),
                metadata = listOfNotNull(request.customerReference?.let { "customerReference" to it }).toMap(),
                requestCardAlias = tokenization != null && request.scannedPayment == null,
                // A tip on the receipt is captured later with the tip, as Adyen's tipping on the receipt flow asks.
                preAuthorisation = request.manualCapture,
                scannedPayment = request.scannedPayment,
            ),
            expected = request.walletContext,
            setupIdentity = request.walletSetupIdentity,
        )
    }

    override fun release(request: PaymentStart) {
        request.scannedPayment?.discard()
    }

    override suspend fun sending(
        id: String,
        poiId: String,
    ) = sales.record(id, SaleEvent.Sending(poiId))

    override suspend fun settle(
        id: String,
        settlement: Settlement,
    ) {
        val status =
            when (settlement.status) {
                SettlementStatus.SUCCEEDED -> SaleStatus.APPROVED
                SettlementStatus.CANCELLED -> SaleStatus.CANCELLED
                SettlementStatus.DECLINED -> SaleStatus.DECLINED
                SettlementStatus.FAILED -> SaleStatus.FAILED
                SettlementStatus.UNKNOWN -> SaleStatus.UNKNOWN
            }
        sales.record(id, SaleEvent.Settled(status, settlement.message, settlement.details, settlement.reason))
    }

    override suspend fun unsettledServiceId(id: String): String? =
        sales
            .get(id)
            ?.sale
            ?.takeIf { it.status == SaleStatus.UNKNOWN }
            ?.serviceId

    /** Terminal API values and conversions shared with refunds, and the stored form of a payment, shared with links. */
    companion object {
        /**
         * The `TenderOption` that makes the terminal leave receipt printing to the app, which prints one combined slip
         * with the card receipt lines instead.
         */
        const val RECEIPT_HANDLER = "ReceiptHandler"

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
            )
        }

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
                )
            }
    }
}
