package app.minimpos.app

import app.minimpos.app.ArchitectureTest.Companion.CONTAINER
import app.minimpos.app.ArchitectureTest.Companion.UI_PACKAGES
import app.minimpos.app.ArchitectureTest.Companion.app
import app.minimpos.app.ArchitectureTest.Companion.callTo
import app.minimpos.app.ArchitectureTest.Companion.callToSubtypeOf
import app.minimpos.app.ArchitectureTest.Companion.declaredIn
import app.minimpos.app.ArchitectureTest.Companion.within
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.RefundStart
import app.minimpos.app.payment.Captures
import app.minimpos.app.payment.CheckoutBook
import app.minimpos.app.payment.CheckoutRefunds
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.app.payment.TerminalReconciliation
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.Destination
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.terminal.PaymentsAppDestination
import app.minimpos.app.terminal.SimulatedTerminal
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalSetup
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.UnlockedSetup
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.PaymentLinkApi
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTransport
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership of the deep modules that hide how an operation or setup reading works: financial operations, the
 * stored-payment reading, the setup fence, Management and Checkout slices and destination capabilities. Each rule has
 * a deliberate violation that must fail it.
 */
class OwnershipArchitectureTest {
    @Test
    fun `financial operations are checked again, routed and counted in one place`() = financialOperationOwnership.forEach { it.check(app) }

    @Test
    fun `financial operation ownership rejects deliberate violations`() =
        reject(financialOperationOwnership, FinancialOperationViolation::class.java)

    private class FinancialOperationViolation {
        suspend fun recheck(payments: TransactionLifecycle<PaymentStart>) = payments.recheck("ID")

        fun refund(
            refunds: CheckoutRefunds,
            request: RefundStart,
        ) = refunds.start(request)

        fun running(charges: SavedCardCharges) = charges.state.value

        fun gate(sale: SaleEntity) = sale.serviceId

        suspend fun charge(
            api: SavedCardApi,
            request: CardChargeRequest,
        ) = api.charge(request, "KEY")
    }

    @Test
    fun `issue meaning and day grouping are read from the stored-payment reading`() = storedReading.forEach { it.check(app) }

    @Test
    fun `stored reading ownership rejects re-derived issues and screen-side grouping`() =
        reject(storedReading, StoredReadingViolation::class.java)

    private class StoredReadingViolation {
        fun adjustment(sale: SaleEntity) = sale.adjustmentPending

        fun capture(standing: PaymentStanding) = standing == PaymentStanding.CAPTURE_UNKNOWN

        fun group(items: List<HistoryItem>) = HistoryAccounting.activities(items)
    }

    @Test
    fun `work begun on one setup checks it is still stored through its fence`() = setupFence.forEach { it.check(app) }

    @Test
    fun `setup fence ownership rejects direct fingerprint comparisons and settable callbacks`() =
        reject(setupFence, SetupFenceViolation::class.java)

    private class SetupFenceViolation {
        var onVerified: () -> Unit = {}

        fun stale(
            origin: UnlockedSetup,
            actual: UnlockedSetup,
        ) = origin.validationIdentity != actual.validationIdentity
    }

    @Test
    fun `each Management task gets only the narrow interface its job needs`() = managementSlices.check(app)

    @Test
    fun `management slice ownership rejects a task holding every Management job`() =
        reject(listOf(managementSlices), ManagementSliceViolation::class.java)

    private class ManagementSliceViolation(
        val management: TerminalDetailsApi,
    )

    @Test
    fun `the Checkout API is made once per credential`() =
        // AdyenCheckout makes modifications, links and saved cards for one credential, sharing its HTTP client; the app
        // never builds one of them on its own.
        checkoutAdapters.check(app)

    @Test
    fun `checkout adapter ownership rejects a separately built part`() =
        reject(listOf(checkoutAdapters), CheckoutAdapterViolation::class.java)

    private class CheckoutAdapterViolation {
        fun make(credentials: CheckoutCredentials) = CheckoutSavedCards(credentials)
    }

    @Test
    fun `destination capabilities travel as one value and adapters are built in one place`() = capabilityValue.forEach { it.check(app) }

    @Test
    fun `capability value ownership rejects copied flags, forged values and separately built adapters`() =
        reject(capabilityValue, CapabilityValueViolation::class.java)

    private class CapabilityValueViolation(
        val supportsShopperDisplay: Boolean,
    ) {
        fun forge() = DestinationCapabilities()

        fun build(reach: (key: TerminalKey, environment: TerminalEnvironment) -> TerminalTransport) = PaymentsAppDestination(reach)
    }

    @Test
    fun `only terminal reconciliation compares local and terminal totals`() =
        // The recheck-unresolved, totals and compare sequence is one operation in payment; screens follow its outcome.
        noClasses()
            .that()
            .haveNameNotMatching(within(TerminalReconciliation::class.java.name))
            .should()
            .callMethodWhere(callTo(HistoryAccounting::class.java.name, "compareReconciliation", "unresolvedTerminalItems"))
            .orShould()
            .callMethodWhere(callTo(TerminalGateway::class.java.name, "totals"))
            .check(app)

    @Test
    fun `only the setup gate decides whether actions wait for setup`() = setupGateOwnership.check(app)

    @Test
    fun `setup gate ownership rejects a screen reading setup readiness itself`() =
        reject(listOf(setupGateOwnership), SetupGateViolation::class.java)

    private class SetupGateViolation {
        fun ready(state: TerminalState) = !state.needsSetup
    }

    @Test
    fun `result and detail screens share their receipt and status check components`() =
        transactionComponentOwnership.forEach { it.check(app) }

    @Test
    fun `transaction component ownership rejects hand-built receipt buttons and status checks`() =
        reject(transactionComponentOwnership, TransactionComponentViolation::class.java)

    private class TransactionComponentViolation {
        fun offers(state: TransactionActionsState) = state.canPrint || state.stillUnknown != null
    }

    private fun reject(
        rules: List<ArchRule>,
        violation: Class<*>,
    ) {
        val imported = ClassFileImporter().importClasses(violation)
        rules.forEach { assertTrue(it.description, it.evaluate(imported).hasViolation()) }
    }

    private companion object {
        // Every gated action asks the one SetupGate, so "not set up yet" is decided, explained and linked the same way
        // on every screen; a screen reading readiness itself would drift into its own wording or partial gate.
        val setupGateOwnership: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within("app.minimpos.app.ui.components.SetupGate", "app.minimpos.app.ui.components.SetupGateKt"))
                .should()
                .callMethodWhere(callTo(TerminalState::class.java.name, "getNeedsSetup"))

        // Receipt buttons and status checks appear in one order, with one wording and one setup gate, on every result and
        // detail screen. An open payment link delivers a payment request rather than a receipt, in the same order.
        val transactionComponentOwnership: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(
                        within("app.minimpos.app.feature.TransactionComponentsKt", "app.minimpos.app.feature.sale.PaymentLinkScreensKt"),
                    ).should()
                    .callMethodWhere(
                        callTo(
                            TransactionActionsState::class.java.name,
                            "getCanPrint",
                            "getCanEmail",
                            "getCanShare",
                            "getMerchantCopyPending",
                        ),
                    ),
                noClasses()
                    .that()
                    .haveNameNotMatching(within("app.minimpos.app.feature.TransactionComponentsKt"))
                    .should()
                    .callMethodWhere(callTo(TransactionActionsState::class.java.name, "getStillUnknown", "getRechecking")),
            )

        // FinancialOperations knows how each stored operation was sent, so status checks, refund routing and whether
        // anything is being sent cannot drift apart between callers; screens offer a check from the stored-payment
        // reading, not the terminal's ServiceID; and Checkout charges and refunds share one runner's recovery rules.
        val financialOperationOwnership: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(
                        // A suspend function's continuation calls the function itself.
                        within(
                            FinancialOperations::class.java.name,
                            TransactionLifecycle::class.java.name,
                            SavedCardCharges::class.java.name,
                            CheckoutRefunds::class.java.name,
                        ),
                    ).should()
                    .callMethodWhere(
                        callTo(TransactionLifecycle::class.java.name, "recheck")
                            .or(callTo(SavedCardCharges::class.java.name, "recheck"))
                            .or(callTo(CheckoutRefunds::class.java.name, "recheck")),
                    ),
                noClasses()
                    .that()
                    .haveNameNotMatching(within(FinancialOperations::class.java.name, CheckoutRefunds::class.java.name))
                    .and()
                    .haveNameNotMatching(CONTAINER)
                    .should()
                    .dependOnClassesThat()
                    .belongToAnyOf(CheckoutRefunds::class.java),
                noClasses()
                    .that(
                        DescribedPredicate
                            .describe<JavaClass>("the container") { it.name.matches(Regex(CONTAINER)) }
                            .or(type(FinancialOperationViolation::class.java)),
                    ).should()
                    .callMethodWhere(
                        callTo(TransactionLifecycle::class.java.name, "getState")
                            .or(callTo(SavedCardCharges::class.java.name, "getState"))
                            .or(callTo(CheckoutRefunds::class.java.name, "getState")),
                    ),
                noClasses()
                    .that(resideInAnyPackage(*UI_PACKAGES).or(type(FinancialOperationViolation::class.java)))
                    .should()
                    .callMethodWhere(
                        callTo(SaleEntity::class.java.name, "getServiceId").or(callTo(RefundEntity::class.java.name, "getServiceId")),
                    ),
                noClasses()
                    .that()
                    .areNotAssignableTo(CheckoutBook::class.java)
                    .should()
                    .callMethodWhere(
                        callToSubtypeOf(SavedCardApi::class.java, "charge")
                            .or(callToSubtypeOf(PaymentModifications::class.java, "refund"))
                            .or(callToSubtypeOf(PaymentLinkApi::class.java, "create")),
                    ),
            )

        // What still needs attention is one named reading (SaleEntity.issues), so day totals, the issue filter and
        // reconciliation cannot drift apart; screens get whole days from HistoryAccounting rather than grouping
        // activities themselves. Captures reads a pending adjustment to reuse its identity, not as an issue.
        val storedReading: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .resideOutsideOfPackage("app.minimpos.app.data..")
                    .and(not(declaredIn("app.minimpos.app.history", "PaymentStanding.kt")))
                    .and()
                    .haveNameNotMatching(within(Captures::class.java.name))
                    .should()
                    .callMethodWhere(callTo(SaleEntity::class.java.name, "getAdjustmentPending")),
                noClasses()
                    .that(declaredIn("app.minimpos.app.history", "HistoryAccounting.kt").or(type(StoredReadingViolation::class.java)))
                    .should()
                    .accessField(PaymentStanding::class.java, PaymentStanding.CAPTURE_FAILED.name)
                    .orShould()
                    .accessField(PaymentStanding::class.java, PaymentStanding.CAPTURE_UNKNOWN.name)
                    .orShould()
                    .accessField(PaymentStanding::class.java, PaymentStanding.CAPTURE_SENDING.name),
                noClasses()
                    .that(resideInAnyPackage(*UI_PACKAGES).or(type(StoredReadingViolation::class.java)))
                    .should()
                    .callMethodWhere(callTo(HistoryAccounting::class.java.name, "activities", "totals")),
            )

        // Whether the setup work began on is still stored, and to which strength, is decided by SetupFence, so tasks
        // cannot pick a weaker comparison by accident; the gateway checks a scan's checked identity before sending. The
        // setup source takes everything it calls as constructor arguments: no module can change it after construction.
        val setupFence: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(
                        within(
                            UnlockedSetup::class.java.name,
                            TerminalSetupSource.SetupFence::class.java.name,
                            TerminalSetupSource::class.java.name,
                            TerminalGateway::class.java.name,
                        ),
                    ).should()
                    .callMethodWhere(callTo(UnlockedSetup::class.java.name, "getValidationIdentity")),
                // Only the source's own fields: its suspend functions' continuations keep their state in mutable fields.
                noFields()
                    .that()
                    .areDeclaredIn(TerminalSetupSource::class.java)
                    .or()
                    .areDeclaredIn(SetupFenceViolation::class.java)
                    .should()
                    .notBeFinal(),
            )

        // Listing, shared-key and capability tasks receive TerminalListingApi, SharedKeyApi or TerminalCapabilitiesApi,
        // so the type decides what each may call; only the container holds every Management job to hand out.
        val managementSlices: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(CONTAINER)
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(TerminalDetailsApi::class.java)

        val checkoutAdapters: ArchRule =
            noClasses()
                .should()
                .callConstructorWhere(
                    target(
                        owner(
                            belongToAnyOf(
                                CheckoutModifications::class.java,
                                CheckoutPaymentLinks::class.java,
                                CheckoutSavedCards::class.java,
                            ),
                        ),
                    ),
                )

        // A destination capability is added once, to DestinationCapabilities, which only the resolved setup reads from
        // its rules (TerminalState's default is the not-yet-loaded state) and which the status carries whole instead of
        // copying flags; the gateway builds every destination's adapter in one function for itself and candidates.
        val capabilityValue: List<ArchRule> =
            listOf(
                // AdyenCapabilitiesState carries the value whole too, so its default constructs one; the status's own
                // state is the not-yet-loaded default.
                noClasses()
                    .that()
                    .haveNameNotMatching(
                        within(
                            TerminalSetup::class.java.name,
                            DestinationCapabilities::class.java.name,
                            TerminalState::class.java.name,
                            AdyenCapabilitiesState::class.java.name,
                        ),
                    ).should()
                    .callConstructorWhere(target(owner(type(DestinationCapabilities::class.java)))),
                noFields()
                    .that()
                    .areDeclaredIn(TerminalState::class.java)
                    .or()
                    .areDeclaredIn(CapabilityValueViolation::class.java)
                    .should()
                    .haveNameMatching(
                        "supports.*|selectsEnvironment|discoversTerminals|scannedWallets|checksConnection",
                    ),
                classes().should(
                    object : ArchCondition<JavaClass>("build destination adapters only in TerminalGateway's adapters") {
                        override fun check(
                            javaClass: JavaClass,
                            events: ConditionEvents,
                        ) {
                            javaClass.constructorCallsFromSelf
                                .filter {
                                    it.targetOwner.isAssignableTo(Destination::class.java) &&
                                        !it.targetOwner.isEquivalentTo(SimulatedTerminal::class.java)
                                }.forEach { call ->
                                    // The gateway's one adapters function value: a nested lambda class or its synthetic method.
                                    val gateway = TerminalGateway::class.java.name
                                    val inAdapters =
                                        call.originOwner.name.startsWith("$gateway\$adapters") ||
                                            (call.originOwner.name == gateway && call.origin.name.startsWith("adapters"))
                                    events.add(SimpleConditionEvent(call, inAdapters, call.description))
                                }
                        }
                    },
                ),
            )
    }
}
