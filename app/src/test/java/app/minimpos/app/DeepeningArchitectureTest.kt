package app.minimpos.app

import app.minimpos.app.ArchitectureTest.Companion.app
import app.minimpos.app.ArchitectureTest.Companion.callTo
import app.minimpos.app.ArchitectureTest.Companion.callToSubtypeOf
import app.minimpos.app.ArchitectureTest.Companion.within
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.repo.RefundBalance
import app.minimpos.app.data.repo.returnsTo
import app.minimpos.app.data.security.ManagerPolicy
import app.minimpos.app.data.security.PinManager
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.NewInstallation
import app.minimpos.app.data.settings.SecuritySettings
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.capture.CaptureViewModel
import app.minimpos.app.feature.capture.TipViewModel
import app.minimpos.app.feature.history.SaleDetailViewModel
import app.minimpos.app.feature.persisting
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.ReceiptStanding
import app.minimpos.app.history.RefundStart
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.payment.AutoDelivery
import app.minimpos.app.payment.Captures
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutBook
import app.minimpos.app.payment.CheckoutOperations
import app.minimpos.app.payment.CheckoutRefunds
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.IdentifiedSend
import app.minimpos.app.payment.OperationKind
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.RefundCancellations
import app.minimpos.app.payment.SaleBook
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.app.payment.SavedCardLookup
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.payment.TerminalSends
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.receipt.ReceiptFactory
import app.minimpos.app.receipt.ReceiptKind
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.CapabilityDiscovery
import app.minimpos.app.terminal.DestinationRules
import app.minimpos.app.terminal.SetupDiscovery
import app.minimpos.app.terminal.SetupFreshness
import app.minimpos.app.terminal.SharedKeySetupOutcome
import app.minimpos.app.terminal.SimulatedTerminal
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.app.terminal.UnlockedSetup
import app.minimpos.app.terminal.WalletDiscovery
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.StarterTax
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.PaymentLinkApi
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.transport.AdyenLocalTransport
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.Fault
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caller knowledge absorbed by capture execution, saved-card lookup, shared-key completion and automatic receipt delivery. */
class DeepeningArchitectureTest {
    @Test
    fun `only committed history removal wiring releases process registries`() = historyRemoval.check(app)

    @Test
    fun `history lifetime rejects a caller discarding retained operations`() =
        reject(listOf(historyRemoval), HistoryRemovalViolation::class.java)

    private class HistoryRemovalViolation {
        fun forget(receipts: ReceiptDelivery) = receipts.forget(setOf("unresolved"))

        fun purge(receipts: ReceiptDelivery) = receipts.forgetAll()
    }

    @Test
    fun `only terminal sends release late answers, after durable settlement`() = lateAnswerLifetime.check(app)

    @Test
    fun `late answer lifetime rejects premature caller acknowledgement`() =
        reject(listOf(lateAnswerLifetime), LateAnswerViolation::class.java)

    private class LateAnswerViolation {
        fun release(attempt: Attempt.Made<*>) = attempt.onStored()
    }

    @Test
    fun `stored terminal requests are sent, aborted and checked again only by terminal sends`() = terminalSends.check(app)

    @Test
    fun `terminal send ownership rejects a runner calling the gateway itself`() =
        reject(listOf(terminalSends), TerminalSendViolation::class.java)

    private class TerminalSendViolation {
        suspend fun recheck(gateway: TerminalGateway) = gateway.status("service", TransactionKind.PAYMENT)
    }

    @Test
    fun `only operation identity decides what a failed send of an identified operation establishes`() = operationIdentity.check(app)

    @Test
    fun `operation identity rejects an operation reading whether its failure took effect`() =
        reject(listOf(operationIdentity), OperationIdentityViolation::class.java)

    private class OperationIdentityViolation {
        fun unchanged(fault: Fault) = !fault.mayHaveTakenEffect
    }

    @Test
    fun `identified Checkout operations are exchanged only inside the operation identity's send`() =
        identifiedSends.forEach { it.check(app) }

    @Test
    fun `identified send ownership rejects a capture or book sent outside the protocol`() =
        reject(identifiedSends, IdentifiedSendViolation::class.java)

    private class IdentifiedSendViolation {
        suspend fun capture(modifications: PaymentModifications) =
            modifications.capture("PSP", ModificationAmount("AUD", 1), "R", "capture-s-1")

        suspend fun resend(
            book: CheckoutBook<RefundStart, PaymentModifications>,
            modifications: PaymentModifications,
        ) = book.send("r", modifications, 0)
    }

    @Test
    fun `payments, history and receipts read how a stored record was sent only from its route`() = storedRoutes.check(app)

    @Test
    fun `route ownership rejects a status check routed on the stored flags`() = reject(listOf(storedRoutes), RouteViolation::class.java)

    private class RouteViolation {
        fun checkout(refund: RefundEntity) = refund.checkoutRefund

        fun link(sale: SaleEntity) = sale.paymentLink
    }

    @Test
    fun `card features' Manager permissions come only from the card-feature table`() = cardFeatures.check(app)

    @Test
    fun `card feature ownership rejects a payment deciding special-entry approval itself`() =
        reject(listOf(cardFeatures), CardFeatureViolation::class.java)

    private class CardFeatureViolation {
        fun actions() = setOf(ManagerAction.MANUAL_ENTRY)
    }

    @Test
    fun `only the refund balance decides which returns count against a tender`() = refundBalance.check(app)

    @Test
    fun `refund balance ownership rejects a caller matching returns to tenders itself`() =
        reject(listOf(refundBalance), RefundBalanceViolation::class.java)

    private class OperationRegistryViolation {
        fun busy(charges: SavedCardCharges) = charges.managing

        fun release(kind: OperationKind) = kind.forgetAll()
    }

    @Test
    fun `financial operations hold every runner through its operation kind`() = operationRegistry.forEach { it.check(app) }

    @Test
    fun `operation registry rejects reading a runner's own flag or releasing its requests elsewhere`() =
        reject(operationRegistry, OperationRegistryViolation::class.java)

    @Test
    fun `wallet and capability readings follow their setup only through the setup reading`() = setupReadings.check(app)

    @Test
    fun `setup reading ownership rejects a reading fencing its setup itself`() =
        reject(listOf(setupReadings), SetupReadingViolation::class.java)

    private class SetupReadingViolation {
        fun fence(
            setups: TerminalSetupSource,
            origin: UnlockedSetup,
        ) = setups.SetupFence(origin, SetupFreshness.SECRETS)
    }

    @Test
    fun `waiting screens move on from a finished operation only by following its progress`() = followProgress.check(app)

    @Test
    fun `progress following rejects a screen reading a finished operation itself`() =
        reject(listOf(followProgress), FollowProgressViolation::class.java)

    private class FollowProgressViolation {
        fun result(state: TransactionState.Finished) = state.id
    }

    private class RefundBalanceViolation {
        fun reserved(
            refund: RefundEntity,
            tender: SaleTenderEntity,
        ) = refund.returnsTo(tender)
    }

    @Test
    fun `test environment cannot fall back to real terminal or saved card transports`() =
        offlineFixture.check(ClassFileImporter().importClasses(TestEnvironment::class.java))

    @Test
    fun `offline fixture ownership rejects a real saved card adapter`() =
        reject(listOf(offlineFixture), NetworkFixtureViolation::class.java)

    private class NetworkFixtureViolation {
        fun cards(credentials: CheckoutCredentials) = CheckoutSavedCards(credentials)
    }

    @Test
    fun `capture callers do not supply financial cancellation protection`() = captureLifetime.check(app)

    @Test
    fun `capture lifetime ownership rejects caller shielding`() = reject(listOf(captureLifetime), CaptureLifetimeViolation::class.java)

    private class CaptureLifetimeViolation {
        suspend fun shield() = persisting {}
    }

    @Test
    fun `saved card listing selection and model eligibility belong to the lookup`() = savedCardLookup.forEach { it.check(app) }

    @Test
    fun `saved card ownership rejects raw listing and duplicated model eligibility`() =
        reject(savedCardLookup, SavedCardLookupViolation::class.java)

    private class SavedCardLookupViolation {
        suspend fun find(charges: SavedCardCharges) = charges.cards("CUSTOMER")

        fun eligible(card: StoredCard) = card.supports("Subscription")
    }

    @Test
    fun `shared key completion hides unverified keys and uses the source exact key fence`() = sharedKeyCompletion.forEach { it.check(app) }

    @Test
    fun `shared key completion rejects callers interpreting a raw key or rebasing its fence`() =
        reject(sharedKeyCompletion, SharedKeyCompletionViolation::class.java)

    private class SharedKeyCompletionViolation {
        fun raw(ready: SharedKeySetupOutcome.Ready) = ready.key

        suspend fun commit(
            fence: TerminalSetupSource.SetupFence,
            key: DiscoveredKey,
        ) = fence.withSharedKey(key)
    }

    @Test
    fun `automatic receipt claims use transaction actions and only delivery publishes progress`() =
        receiptAutomation.forEach { it.check(app) }

    @Test
    fun `receipt ownership rejects screen side claims and invented automatic progress`() =
        reject(receiptAutomation, ReceiptAutomationViolation::class.java)

    private class ReceiptAutomationViolation {
        fun claim(
            receipts: ReceiptDelivery,
            transaction: StoredTransaction,
        ) = receipts.automation(transaction)

        fun dispatch(progress: AutoDelivery) = progress.copy(emailing = true)
    }

    @Test
    fun `financial operations decide Manager approval only through the approval they started with`() = managerApproval.check(app)

    @Test
    fun `approval ownership rejects operations reading the screen-scoped Manager session`() =
        reject(listOf(managerApproval), ManagerApprovalViolation::class.java)

    private class ManagerApprovalViolation {
        fun approved(session: SessionLock) = session.allows(0)
    }

    @Test
    fun `whether actions need or can get Manager approval is read only from the Manager policy`() = managerPolicy.check(app)

    @Test
    fun `Manager policy ownership rejects a caller deciding a requirement from the stored choice itself`() =
        reject(listOf(managerPolicy), ManagerPolicyViolation::class.java)

    private class ManagerPolicyViolation {
        fun required(
            admin: Boolean,
            settings: SecuritySettings,
        ) = admin && settings.requiresManager(ManagerAction.CAPTURE)
    }

    @Test
    fun `screens follow running payments and refunds only through financial operations`() = operationProgress.check(app)

    @Test
    fun `progress ownership rejects screens following or leaving a runner themselves`() =
        reject(listOf(operationProgress), ProgressViolation::class.java)

    private class ProgressViolation {
        fun follow(charges: SavedCardCharges) = charges.state
    }

    @Test
    fun `regional new-installation defaults are decided only by NewInstallation`() = newInstallationDefaults.check(app)

    @Test
    fun `new-installation ownership rejects a regional default decided elsewhere`() =
        reject(listOf(newInstallationDefaults), NewInstallationViolation::class.java)

    private class NewInstallationViolation {
        fun rate(country: String) = StarterTax.forCountry(country)
    }

    @Test
    fun `checkout screens follow each tender's ceremony instead of deciding confirmations themselves`() = tenderCeremony.check(app)

    @Test
    fun `tender ceremony ownership rejects a screen deciding special entry approval itself`() =
        reject(listOf(tenderCeremony), TenderCeremonyViolation::class.java)

    private class TenderCeremonyViolation {
        fun approve(checkout: Checkout) = checkout.requiresSpecialEntryApproval
    }

    @Test
    fun `a receipt's kind decides both its document and its email envelope`() = receiptKind.check(app)

    @Test
    fun `receipt kind ownership rejects delivery choosing an envelope from the standing itself`() =
        reject(listOf(receiptKind), ReceiptKindViolation::class.java)

    private class ReceiptKindViolation {
        fun envelope(standing: ReceiptStanding) = standing.preAuthorisation
    }

    @Test
    fun `terminal requests and answers are read in minor units, converted only by the client`() = terminalMoney.check(app)

    @Test
    fun `terminal money ownership rejects a terminal reading converting units itself`() =
        reject(listOf(terminalMoney), TerminalMoneyViolation::class.java)

    private class TerminalMoneyViolation {
        fun major(spec: CurrencySpec) = spec.toMajor(100)
    }

    @Test
    fun `settings render the terminal setup plan instead of deciding its steps`() = setupPlan.check(app)

    @Test
    fun `setup plan ownership rejects a screen reading supplied details itself`() =
        reject(listOf(setupPlan), SetupPlanViolation::class.java)

    private class SetupPlanViolation {
        fun supplied(rules: DestinationRules) = rules.suppliedDetails(TerminalSettings(), emptySet())
    }

    @Test
    fun `only the setup source compares the verified setup fingerprint`() = verifiedSetup.check(app)

    @Test
    fun `verified setup ownership rejects a fingerprint compared elsewhere`() =
        reject(listOf(verifiedSetup), VerifiedSetupViolation::class.java)

    private class VerifiedSetupViolation {
        fun verified(
            settings: AppSettings,
            identity: String,
        ) = settings.verifiedSetup == identity
    }

    private fun reject(
        rules: List<ArchRule>,
        violation: Class<*>,
    ) {
        val imported = ClassFileImporter().importClasses(violation)
        rules.forEach { assertTrue(it.description, it.evaluate(imported).hasViolation()) }
    }

    private companion object {
        // Cleanup must follow a committed history deletion, never a screen's guess about an unknown outcome.
        val historyRemoval: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within(AppContainer::class.java.name))
                .should()
                .callMethodWhere(callTo(FinancialOperations::class.java.name, "forget", "forgetAll"))
                .orShould()
                .callMethodWhere(callTo(ReceiptDelivery::class.java.name, "forget", "forgetAll"))
                .orShould()
                .callMethodWhere(callTo(SimulatedTerminal::class.java.name, "forget", "forgetAll"))

        // A late Tap to Pay answer is the only recovery evidence; release it only after durable financial settlement.
        val lateAnswerLifetime: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within(TerminalSends::class.java.name))
                .should()
                .callMethodWhere(callTo("app.minimpos.app.terminal.Attempt\$Made", "getOnStored"))

        // Each Checkout book, capture and refund cancellation once wrote its own "a resend that provably did nothing leaves
        // the unknown operation alone" guard; OperationIdentity decides what a failed send establishes, so none can drift.
        val operationIdentity: ArchRule =
            noClasses()
                .that()
                .haveNameMatching(
                    within(
                        "app.minimpos.app.payment.SavedCardCharges\$Book",
                        "app.minimpos.app.payment.CheckoutRefunds\$Book",
                        PaymentLinks::class.java.name,
                        CheckoutOperations::class.java.name,
                        Captures::class.java.name,
                        RefundCancellations::class.java.name,
                        OperationIdentityViolation::class.java.name,
                    ),
                ).should()
                .callMethodWhere(callToSubtypeOf(Fault::class.java, "getMayHaveTakenEffect"))

        // Captures, adjustments, refund cancellations and link expiry each wrote their own approval, setup, replay-window,
        // persistence and failure ordering around those decisions, and the copies disagreed; OperationIdentity.send runs
        // that order once, so money-moving Checkout calls are made only by its adapters and the books they send.
        val identifiedSends: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .doNotImplement(IdentifiedSend::class.java)
                    .and()
                    .doNotImplement(CheckoutBook::class.java)
                    .should()
                    .callMethodWhere(
                        callTo(PaymentModifications::class.java.name, "capture", "updateAmount", "refund", "cancelPendingRefund"),
                    ).orShould()
                    .callMethodWhere(callTo(SavedCardApi::class.java.name, "charge"))
                    .orShould()
                    .callMethodWhere(callTo(PaymentLinkApi::class.java.name, "create", "expire")),
                noClasses()
                    .that()
                    .doNotImplement(IdentifiedSend::class.java)
                    .should()
                    .callMethodWhere(callTo(CheckoutBook::class.java.name, "send")),
            )

        // Status-check routing, check offers, book selection, terminal totals and receipts each recombined the stored
        // payment-link, saved-card, Checkout-refund, unreferenced and cash flags, so the check offered and the check routed
        // had to agree by hand; SaleRoute and RefundRoute read them once and every site switches on the route.
        val storedRoutes: ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage("app.minimpos.app.payment..", "app.minimpos.app.history..", "app.minimpos.app.receipt..")
                .or()
                .haveNameMatching(within(RouteViolation::class.java.name))
                .should()
                .callMethodWhere(callTo(SaleEntity::class.java.name, "getPaymentLink", "getSavedCard"))
                .orShould()
                .callMethodWhere(callTo(RefundEntity::class.java.name, "getCheckoutRefund", "getUnreferenced", "getCash"))

        // Checkout and the payment request each derived MOTO/MKE and cash permissions, and PaymentStart restated every
        // feature pair; CardFeatures states them once, so offers, validation, destination checks and approval agree.
        val cardFeatures: ArchRule =
            noClasses()
                .that(
                    resideInAnyPackage(
                        "app.minimpos.app.payment..",
                        "app.minimpos.app.terminal..",
                    ).or(type(CardFeatureViolation::class.java)),
                ).should()
                .accessField(ManagerAction::class.java, ManagerAction.MANUAL_ENTRY.name)

        // Staff review and the storing transaction once computed a tender's remaining balance separately, agreeing only
        // by accident; both now ask RefundBalance, the one reading of which returns count against a sale or tender.
        val refundBalance: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within(RefundBalance::class.java.name, "app.minimpos.app.data.repo.RefundBalanceKt"))
                .should()
                .callMethodWhere(callTo("app.minimpos.app.data.repo.RefundBalanceKt", "returnsTo"))

        // FinancialOperations once read eleven differently named runner flags and kept three hand-written lists, so a new
        // runner could be left out of one; every runner now fills one OperationKind, released only by FinancialOperations.
        val operationRegistry: List<ArchRule> =
            listOf(
                noClasses()
                    .that(belongToAnyOf(FinancialOperations::class.java).or(type(OperationRegistryViolation::class.java)))
                    .should()
                    .callMethodWhere(
                        DescribedPredicate.describe<JavaMethodCall>("a runner's own busy flag or request release") { call ->
                            call.targetOwner.packageName == "app.minimpos.app.payment" &&
                                call.targetOwner.name != OperationKind::class.java.name &&
                                call.name.substringBefore('$') in
                                setOf("getChecking", "getManaging", "getActive", "getRunning", "forget", "forgetAll")
                        },
                    ),
                noClasses()
                    .that()
                    .haveNameNotMatching(within(FinancialOperations::class.java.name))
                    .should()
                    .callMethodWhere(callTo(OperationKind::class.java.name, "forget", "forgetAll")),
            )

        // Wallet and capability discovery each repeated the same fenced refresh and dropped stale results differently;
        // SetupReading alone decides reuse for the same setup, fenced publishing and that checking always ends.
        val setupReadings: ArchRule =
            noClasses()
                .that(
                    belongToAnyOf(WalletDiscovery::class.java, CapabilityDiscovery::class.java).or(type(SetupReadingViolation::class.java)),
                ).should()
                .callConstructorWhere(
                    DescribedPredicate.describe<JavaConstructorCall>("a setup fence") {
                        it.targetOwner.isEquivalentTo(TerminalSetupSource.SetupFence::class.java)
                    },
                )

        // Payment, refund and charge waiting screens each wrote their own Finished/Idle navigation; FollowProgress is the
        // one reading of how a waiting screen moves on, so they cannot drift apart.
        val followProgress: ArchRule =
            noClasses()
                .that(
                    resideInAnyPackage("app.minimpos.app.feature..", "app.minimpos.app.ui..")
                        .and(
                            DescribedPredicate.not(
                                DescribedPredicate.describe<JavaClass>("the progress follower") {
                                    it.name.matches(Regex(within("app.minimpos.app.feature.OperationProgressKt")))
                                },
                            ),
                        ).or(type(FollowProgressViolation::class.java)),
                ).should()
                .callMethodWhere(callTo(TransactionState.Finished::class.java.name, "getId"))

        // Gift cards once copied the payment lifecycle's send, abort and status check and dropped the late-answer
        // release; every runner of stored terminal requests now decides only what it stores, never how it is sent.
        val terminalSends: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within(TerminalSends::class.java.name, TerminalGateway::class.java.name))
                .should()
                .callMethodWhere(callTo(TerminalGateway::class.java.name, "pay", "refund", "storedValue", "status", "abort"))

        // Missing fake wiring must fail offline, never turn a unit/UI test into a real network request.
        val offlineFixture: ArchRule =
            noClasses().should().dependOnClassesThat().belongToAnyOf(AdyenLocalTransport::class.java, CheckoutSavedCards::class.java)

        // Financial lifetime must not depend on every tip, capture or history caller remembering a UI write helper.
        val captureLifetime: ArchRule =
            noClasses()
                .that()
                .haveNameMatching(
                    within(
                        TipViewModel::class.java.name,
                        CaptureViewModel::class.java.name,
                        SaleDetailViewModel::class.java.name,
                        CaptureLifetimeViolation::class.java.name,
                    ),
                ).should()
                .callMethodWhere(callTo("app.minimpos.app.feature.ViewModelWritesKt", "persisting", "launchWrite"))

        // Listing freshness and eligible-card selection are one operation; Checkout still validates pure request facts.
        val savedCardLookup: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(within(SavedCardLookup::class.java.name, SavedCardCharges::class.java.name))
                    .should()
                    .callMethodWhere(callTo(SavedCardCharges::class.java.name, "cards", "start")),
                noClasses()
                    .that()
                    .haveNameNotMatching(within(SavedCardLookup::class.java.name, Checkout::class.java.name, StoredCard::class.java.name))
                    .should()
                    .callMethodWhere(callTo(StoredCard::class.java.name, "supports")),
            )

        // A raw Ready key is not a verified connection; only the saved-setup operation assembles completion and recovery.
        val sharedKeyCompletion: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .resideOutsideOfPackage("app.minimpos.app.terminal..")
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(SharedKeySetupOutcome::class.java),
                noClasses()
                    .that()
                    .haveNameNotMatching(within(SetupDiscovery::class.java.name, TerminalSetupSource.SetupFence::class.java.name))
                    .should()
                    .callMethodWhere(callTo(TerminalSetupSource.SetupFence::class.java.name, "withSharedKey")),
            )

        // The Manager session closes when navigation leaves manager screens; an operation started while it was open keeps
        // that approval through ManagerApproval instead of rereading the session, which could refuse what was approved.
        val managerApproval: ArchRule =
            noClasses()
                .that()
                .resideInAPackage("app.minimpos.app.payment..")
                .or()
                .haveNameMatching(within(ManagerApprovalViolation::class.java.name))
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(SessionLock::class.java, PinManager::class.java)

        // The container, PIN entry and a test-only approval mode each decided whether an action needs or can get a Manager
        // PIN, and the test mode refused what the app allowed; ManagerPolicy is the one reading, so offers, PIN entry and
        // sends agree. The permission switches show the stored choice, and navigation keeps the Manager session open while
        // a protected route is stacked; neither decides an approval.
        val managerPolicy: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(
                    within(
                        ManagerPolicy::class.java.name,
                        "app.minimpos.app.feature.settings.ManagerPermissionsKt",
                        "app.minimpos.app.ui.navigation.AppNavHostKt",
                    ),
                ).should()
                .callMethodWhere(callTo(SecuritySettings::class.java.name, "requiresManager"))

        // A waiting or result screen that picks a runner re-derives where its amount comes from and which runner to leave;
        // FinancialOperations follows payments and refunds whichever way they are sent, with the amount requested.
        val operationProgress: ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage("app.minimpos.app.feature..", "app.minimpos.app.ui..")
                .or()
                .haveNameMatching(within(ProgressViolation::class.java.name))
                .should()
                .callMethodWhere(callTo(TransactionLifecycle::class.java.name, "getState", "acknowledge", "cancel"))
                .orShould()
                .callMethodWhere(callTo(SavedCardCharges::class.java.name, "getState", "acknowledge"))
                .orShould()
                .callMethodWhere(callTo(CheckoutRefunds::class.java.name, "getState", "acknowledge"))

        // Country and language defaults read the starter tax in one module with a text adapter, so a regional rule (such
        // as Australia's GST-free note) cannot be decided twice with different conditions in the container.
        val newInstallationDefaults: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(within(NewInstallation::class.java.name))
                .should()
                .callMethodWhere(callTo(StarterTax.Companion::class.java.name, "forCountry"))

        // What each tender drops and which confirmations and approval come first is decided next to the copies that drop
        // it (Checkout.ceremony); a screen re-deriving it forgot the donation invitation before.
        val tenderCeremony: ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackage("app.minimpos.app.payment..")
                .should()
                .callMethodWhere(callTo(Checkout::class.java.name, "getRequiresSpecialEntryApproval"))

        // Delivery once re-read the standing's flags to pick an email subject and introduction, so the envelope could
        // disagree with the document; ReceiptKind is the one reading both follow.
        val receiptKind: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(
                    within(ReceiptKind::class.java.name, ReceiptFactory::class.java.name, ReceiptStanding::class.java.name),
                ).should()
                .callMethodWhere(callTo(ReceiptStanding::class.java.name, "getPreAuthorisation"))

        // Amounts cross TerminalClient in minor units and the client converts them with Adyen's decimals once, so the
        // payment, refund, settlement and reconciliation readings never re-apply the conversion themselves.
        val terminalMoney: ArchRule =
            noClasses()
                .that()
                .haveNameMatching(
                    within(
                        SaleBook::class.java.name,
                        RefundablePayment::class.java.name,
                        HistoryAccounting::class.java.name,
                        "app.minimpos.app.data.repo.SaleEventKt",
                        TerminalMoneyViolation::class.java.name,
                    ),
                ).should()
                .callMethodWhere(callTo(CurrencySpec::class.java.name, "toMajor", "toMinor"))

        // Which steps a destination shows, their numbers and when they unlock is one pure reading (TerminalSetupPlan);
        // composables re-deriving it numbered and revealed steps differently for each destination.
        val setupPlan: ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackage("app.minimpos.app.terminal..")
                .should()
                .callMethodWhere(callTo(DestinationRules::class.java.name, "suppliedDetails"))

        // What the verified-setup fingerprint covers is decided once (VerifiedSetup); only the setup source compares
        // and records it, and only the settings write clears it, so a second reading cannot disagree with the gate.
        val verifiedSetup: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(
                    within(TerminalSetupSource::class.java.name, AppSettings::class.java.name, SettingsRepository::class.java.name),
                ).should()
                .callMethodWhere(callTo(AppSettings::class.java.name, "getVerifiedSetup"))

        // First-screen claiming stays shared, while dispatch and its progress cannot be reconstructed in presentation.
        val receiptAutomation: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(within(TransactionActions::class.java.name, ReceiptDelivery::class.java.name))
                    .should()
                    .callMethodWhere(callTo(ReceiptDelivery::class.java.name, "automation")),
                noClasses()
                    .that()
                    .haveNameNotMatching(within(ReceiptDelivery::class.java.name, AutoDelivery::class.java.name))
                    .should()
                    .callMethodWhere(callTo(AutoDelivery::class.java.name, "copy")),
            )
    }
}
