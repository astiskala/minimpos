package app.minimpos.app

import app.minimpos.app.ArchitectureTest.Companion.app
import app.minimpos.app.ArchitectureTest.Companion.callTo
import app.minimpos.app.ArchitectureTest.Companion.within
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.capture.CaptureViewModel
import app.minimpos.app.feature.capture.TipViewModel
import app.minimpos.app.feature.history.SaleDetailViewModel
import app.minimpos.app.feature.persisting
import app.minimpos.app.payment.AutoDelivery
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.app.payment.SavedCardLookup
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.terminal.SetupDiscovery
import app.minimpos.app.terminal.SharedKeySetupOutcome
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.transport.AdyenLocalTransport
import app.minimpos.terminal.transport.DiscoveredKey
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caller knowledge absorbed by capture execution, saved-card lookup, shared-key completion and automatic receipt delivery. */
class DeepeningArchitectureTest {
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

    private fun reject(
        rules: List<ArchRule>,
        violation: Class<*>,
    ) {
        val imported = ClassFileImporter().importClasses(violation)
        rules.forEach { assertTrue(it.description, it.evaluate(imported).hasViolation()) }
    }

    private companion object {
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
