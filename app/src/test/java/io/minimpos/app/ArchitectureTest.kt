package io.minimpos.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.internal.StabilityInferred
import androidx.lifecycle.ViewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.RefundDao
import io.minimpos.app.data.db.SaleDao
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.repo.HistoryRepository
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.repo.RefundRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.ActionOutcome
import io.minimpos.app.feature.TransactionActions
import io.minimpos.app.payment.CaptureResult
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.SaleSession
import io.minimpos.app.receipt.PrintRenderer
import io.minimpos.app.receipt.ReceiptFactory
import io.minimpos.app.refund.PaymentStanding
import io.minimpos.app.refund.StoredPayment
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.Destination
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.app.terminal.SimulatedTerminal
import io.minimpos.app.terminal.TerminalGateway
import io.minimpos.app.terminal.TerminalSetup
import io.minimpos.app.terminal.TerminalSetupSource
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.PrintLine
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TransactionDetails
import io.minimpos.terminal.parse.ReceiptField
import io.minimpos.terminal.simulator.SimulatedOutcome
import io.minimpos.terminal.transport.CloudRegion
import io.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Test
import java.time.Clock

class ArchitectureTest {
    @Test
    fun `layers only depend downwards`() =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(UI)
            .definedBy(*UI_PACKAGES)
            .layer(PAYMENT)
            .definedBy("io.minimpos.app.payment..")
            .layer(EMAIL)
            .definedBy("io.minimpos.app.email..")
            .layer(RECEIPT)
            .definedBy("io.minimpos.app.receipt..")
            .layer(REFUND)
            .definedBy("io.minimpos.app.refund..")
            .layer(TERMINAL)
            .definedBy("io.minimpos.app.terminal..")
            .layer(DATA)
            .definedBy("io.minimpos.app.data..")
            .whereLayer(UI)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(PAYMENT)
            .mayOnlyBeAccessedByLayers(UI)
            .whereLayer(EMAIL)
            .mayOnlyBeAccessedByLayers(PAYMENT, UI)
            .whereLayer(RECEIPT)
            .mayOnlyBeAccessedByLayers(EMAIL, PAYMENT, UI)
            .whereLayer(REFUND)
            .mayOnlyBeAccessedByLayers(RECEIPT, PAYMENT, UI)
            .whereLayer(TERMINAL)
            .mayOnlyBeAccessedByLayers(RECEIPT, PAYMENT, UI)
            .whereLayer(DATA)
            .mayOnlyBeAccessedByLayers(TERMINAL, REFUND, RECEIPT, EMAIL, PAYMENT, UI)
            .check(app)

    @Test
    fun `only the UI reaches into the dependency container`() =
        noClasses()
            .that()
            .resideOutsideOfPackages("io.minimpos.app", "io.minimpos.app.feature..", "io.minimpos.app.ui..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching("io\\.minimpos\\.app\\.(AppContainer|MainActivity|MiniMposApplication)\\b.*")
            .check(app)

    @Test
    fun `features and data packages have no dependency cycles`() {
        slices()
            .matching("io.minimpos.app.feature.(*)..")
            .should()
            .beFreeOfCycles()
            .check(app)
        slices()
            .matching("io.minimpos.app.data.(*)..")
            .should()
            .beFreeOfCycles()
            .check(app)
    }

    @Test
    fun `business logic stays free of Compose`() =
        noClasses()
            .that()
            .resideInAnyPackage(*BUSINESS_PACKAGES)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("androidx.compose..", "androidx.activity..", "androidx.navigation3..")
                    // The Compose compiler adds this annotation to every class in the module.
                    .and(not(type(StabilityInferred::class.java))),
            ).check(app)

    @Test
    fun `view models live in feature packages and hold no Android UI types`() {
        classes()
            .that()
            .areAssignableTo(ViewModel::class.java)
            .should()
            .resideInAPackage("io.minimpos.app.feature..")
            .andShould()
            .haveSimpleNameEndingWith("ViewModel")
            .check(app)
        noClasses()
            .that()
            .areAssignableTo(ViewModel::class.java)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("android.content..", "android.view..", "android.widget..", "androidx.compose.ui..")
            .check(app)
    }

    @Test
    fun `Room stays inside the data layer`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.data..")
            // The container opens the database and hands its DAOs to the repositories.
            .and()
            .haveNameNotMatching(CONTAINER)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(Dao::class.java)
            .orShould()
            .dependOnClassesThat()
            .areAnnotatedWith(Database::class.java)
            .check(app)
        classes()
            .that()
            .areAnnotatedWith(Entity::class.java)
            .or()
            .areAnnotatedWith(Dao::class.java)
            .should()
            .resideInAPackage("io.minimpos.app.data.db..")
            .check(app)
    }

    @Test
    fun `secrets are encrypted in one place`() =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.data.security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("javax.crypto..", "android.security.keystore..")
            .check(app)

    @Test
    fun `the Adyen library stays behind terminal-api`() =
        // terminal-api's interface has its own print model and enums, so nexo knowledge lives in one module.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.adyen..")
            .check(app)

    @Test
    fun `only the terminal package talks to the terminal, the cloud and the Payments app`() {
        // The container plugs the transports, the cloud and the Payments app's links into the gateway and Tap to Pay
        // setup; the activity only serves the bridge's links. Elsewhere the transports, the simulator and the Payments
        // app are only the values the settings store, and a TerminalClient only its helpers for IDs and timestamps.
        noClasses()
            .that()
            .resideOutsideOfPackage(TERMINAL_PACKAGE)
            .and()
            .haveNameNotMatching(CONTAINER)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage(
                    "io.minimpos.terminal.transport..",
                    "io.minimpos.terminal.simulator..",
                    "io.minimpos.terminal.paymentsapp..",
                ).and(not(belongToAnyOf(TerminalEnvironment::class.java, CloudRegion::class.java, SimulatedOutcome::class.java))),
            ).orShould()
            .callMethodWhere(target(owner(type(TerminalClient::class.java))))
            .check(app)
        // Destination.connect makes the one client every request is sent with.
        noClasses()
            .that()
            .haveNameNotMatching(within(Destination::class.java.name))
            .should()
            .callConstructorWhere(target(owner(assignableTo(TerminalClient::class.java))))
            .check(app)
    }

    @Test
    fun `the gateway asks the destination what it can do`() {
        // The gateway makes the destinations it chooses between; the container makes the simulator, whose Checkout API
        // simulation it hands to AdyenApi.
        noClasses()
            .that()
            .haveNameNotMatching(within(TerminalGateway::class.java.name))
            .should()
            .callConstructorWhere(target(owner(assignableTo(Destination::class.java).and(not(type(SimulatedTerminal::class.java))))))
            .check(app)
        noClasses()
            .that()
            .haveNameNotMatching(CONTAINER)
            .should()
            .callConstructorWhere(target(owner(type(SimulatedTerminal::class.java))))
            .check(app)
        noClasses()
            .that(not(declaredIn("io.minimpos.app.terminal", "Destinations.kt")))
            .and()
            .haveNameNotMatching(within(TerminalGateway::class.java.name))
            .should()
            .callMethodWhere(
                callToSubtypeOf(
                    Destination::class.java,
                    "open",
                    "connect",
                    "getAborts",
                    "getDiagnoses",
                    "getRecovery",
                    "transactionTimeout",
                    "connectedTerminals",
                ),
            ).check(app)
    }

    @Test
    fun `the terminal setup is resolved in one place`() {
        noClasses()
            .that()
            .haveNameNotMatching(within(TerminalSetupSource::class.java.name))
            .should()
            .callMethodWhere(callTo(TerminalSetup.Companion::class.java.name, "resolve", "boarding"))
            .check(app)
        // The container makes the one source and hands it to the gateway, the API and the status.
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.terminal..")
            .and()
            .haveNameNotMatching(CONTAINER)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(TerminalSetupSource::class.java)
            .check(app)
    }

    @Test
    fun `only the setup and the gateway's choice of destination tell the remote destinations apart`() =
        // What the cloud or the Payments app can do lives in their Destination; the rest of the logic asks it, not the mode.
        // Only the settings (stored and on screen) offer the modes by name.
        noClasses()
            .that()
            .resideOutsideOfPackages("io.minimpos.app.data.settings..", "io.minimpos.app.feature.settings..")
            .and()
            .haveNameNotMatching(within(TerminalSetup::class.java.name, TerminalGateway::class.java.name))
            .should()
            .accessField(TerminalMode::class.java, TerminalMode.CLOUD.name)
            .orShould()
            .accessField(TerminalMode::class.java, TerminalMode.PAYMENTS_APP.name)
            .check(app)

    @Test
    fun `captures reach the Checkout API through their target`() {
        // Captures take a function for the target, so tests set it directly.
        noClasses()
            .that()
            .resideInAPackage("io.minimpos.app.payment..")
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(AdyenApi::class.java)
            .check(app)
        // The API is handed the simulator's modifications rather than the whole gateway.
        noClasses()
            .that()
            .belongToAnyOf(AdyenApi::class.java)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(TerminalGateway::class.java)
            .check(app)
    }

    @Test
    fun `stored sales and refunds change only through their repositories`() {
        // After it is created, a sale changes only through SaleRepository's named transitions and a refund only through
        // RefundRepository.settle; HistoryRepository owns the housekeeping of whole tables.
        noClasses()
            .that()
            .resideOutsideOfPackage(DB)
            .and()
            .haveNameNotMatching(within(SaleRepository::class.java.name, HistoryRepository::class.java.name))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(SaleDao::class.java)
            .check(app)
        noClasses()
            .that()
            .resideOutsideOfPackage(DB)
            .and()
            .haveNameNotMatching(within(RefundRepository::class.java.name, HistoryRepository::class.java.name))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(RefundDao::class.java)
            .check(app)
    }

    @Test
    fun `only PaymentStanding reads how a capture or a hold ended`() =
        noClasses()
            .that()
            .resideOutsideOfPackage(DB)
            .and()
            .haveNameNotMatching(within(PaymentStanding::class.java.name))
            .should()
            .callMethodWhere(callTo(SaleEntity::class.java.name, "getCaptureStatus", "getHoldCancelled"))
            .check(app)

    @Test
    fun `only the decline reads why a payment was not approved`() =
        // SaleRepository stores the ErrorCondition, SaleEntity.decline reads it back and History shows it as it was sent;
        // cancellations, busy terminals and retry advice come from Decline.
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.data..")
            .and()
            .haveNameNotMatching(within(PAYMENT_STANDING_FILE, "io.minimpos.app.feature.history.HistoryScreensKt"))
            .should()
            .callMethodWhere(callTo(TransactionDetails::class.java.name, "getErrorCondition", "getRefusalReason"))
            .orShould()
            .callMethodWhere(callTo(SaleEntity::class.java.name, "getErrorCondition", "getRefusalReason"))
            .check(app)

    @Test
    fun `only StoredPayment works out what can be done with a payment`() =
        noClasses()
            .that()
            .haveNameNotMatching(within(StoredPayment::class.java.name))
            .should()
            .callMethodWhere(callTo(PAYMENT_STANDING_FILE, "actions"))
            .check(app)

    @Test
    fun `decision rules stay pure`() =
        // Checkout, refund and capture rules, the history search and the terminal setup are tested with plain JUnit.
        noClasses()
            .that(pureDecisions)
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("android..", "androidx..", "kotlinx.coroutines..")
                    .or(simpleNameEndingWith("Repository"))
                    .or(annotatedWith(Dao::class.java))
                    .or(
                        belongToAnyOf(
                            AppDatabase::class.java,
                            SecretStore::class.java,
                            TerminalGateway::class.java,
                            AdyenApi::class.java,
                            Clock::class.java,
                        ),
                    ).and(not(type(StabilityInferred::class.java))),
            ).check(app)

    @Test
    fun `the transaction lifecycle stores only through its book`() =
        noClasses()
            .that(declaredIn("io.minimpos.app.payment", "TransactionLifecycle.kt"))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.minimpos.app.data..")
            .check(app)

    @Test
    fun `screens get a transaction's receipt through TransactionActions`() {
        noClasses()
            .that()
            .haveNameNotMatching(within(TransactionActions::class.java.name, ReceiptDelivery::class.java.name))
            .should()
            .callMethodWhere(
                callTo(
                    ReceiptDelivery::class.java.name,
                    "saleOffer",
                    "refundOffer",
                    "printSale",
                    "printRefund",
                    "emailSale",
                    "emailRefund",
                    "automationForSale",
                    "automationForRefund",
                ),
            ).check(app)
        // The lifecycles arm the automatic delivery through the container.
        noClasses()
            .that()
            .haveNameNotMatching(CONTAINER)
            .should()
            .callMethodWhere(callTo(ReceiptDelivery::class.java.name, "arm"))
            .check(app)
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.app.receipt..")
            .and()
            .haveNameNotMatching(within(ReceiptDelivery::class.java.name))
            .and()
            .haveNameNotMatching(CONTAINER)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(ReceiptFactory::class.java)
            .check(app)
    }

    @Test
    fun `only the Settings screen holds the settings`() =
        // Other UI states carry what they show (such as a receipt offer), not AppSettings or whether there is a printer.
        noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("io.minimpos.app.feature..")
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage("io.minimpos.app.feature.settings..")
            .should()
            .haveRawType(AppSettings::class.java)
            .orShould()
            .haveName("printerAvailable")
            .check(app)

    @Test
    fun `view models and business logic hold no display text`() =
        // Outcomes are typed (ActionOutcome) and worded by the screens; the container hands texts to the modules.
        noClasses()
            .that()
            .areAssignableTo(ViewModel::class.java)
            .or()
            .belongToAnyOf(TransactionActions::class.java)
            .or()
            .resideInAnyPackage(*BUSINESS_PACKAGES)
            // It names the default tax rate of a migration when it opens the database.
            .and()
            .doNotBelongToAnyOf(AppDatabase::class.java)
            .should()
            .dependOnClassesThat()
            .haveNameMatching("io\\.minimpos\\.app\\.R(\\$.*)?")
            .check(app)

    @Test
    fun `a tip, capture or adjustment becomes an action state in one place`() {
        // CaptureResult.toState makes their failures, which OutcomeMessages words.
        noClasses()
            .that()
            .haveNameNotMatching(within(TRANSACTION_ACTIONS_FILE, OUTCOME_MESSAGES_FILE))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(
                ActionOutcome.NotCaptured::class.java,
                ActionOutcome.CaptureRefused::class.java,
                ActionOutcome.CaptureNotAllowed::class.java,
            ).check(app)
        noClasses()
            .that()
            .resideInAPackage("io.minimpos.app.feature..")
            .and()
            .haveNameNotMatching(within(TRANSACTION_ACTIONS_FILE))
            .should()
            .dependOnClassesThat(assignableTo(CaptureResult::class.java).and(not(type(CaptureResult::class.java))))
            .check(app)
    }

    @Test
    fun `setup problems are worded in one place`() {
        // The screens word them through OutcomeMessages; the container words those stored with a transaction or capture.
        noClasses()
            .that()
            .haveNameNotMatching(within(OUTCOME_MESSAGES_FILE))
            .and()
            .haveNameNotMatching(CONTAINER)
            .should()
            .callMethodWhere(callTo(OUTCOME_MESSAGES_FILE, "getTextRes"))
            .check(app)
        noClasses()
            .that()
            .resideInAnyPackage(*UI_PACKAGES)
            .and()
            .haveNameNotMatching(within(OUTCOME_MESSAGES_FILE))
            .should()
            .accessFieldWhere(
                DescribedPredicate.describe("an access to a SetupProblem") { it.targetOwner.isEquivalentTo(SetupProblem::class.java) },
            ).check(app)
    }

    @Test
    fun `each kind of sale has one session, kept by the container`() =
        noClasses()
            .that()
            .haveNameNotMatching(CONTAINER)
            .and()
            .doNotBelongToAnyOf(SaleSession::class.java)
            .should()
            .callConstructorWhere(target(owner(type(SaleSession::class.java))))
            .check(app)

    @Test
    fun `settings are normalized only where they are read and written`() =
        // Each section's ranges live on its companion; SettingsRepository normalizes on every read and write.
        noClasses()
            .that()
            .haveNameNotMatching(within(SettingsRepository::class.java.name, AppSettings::class.java.name))
            .should()
            .callMethodWhere(
                DescribedPredicate.describe("a call to a settings section's normalized()") { call ->
                    call.name == "normalized" && call.targetOwner.packageName == "io.minimpos.app.data.settings"
                },
            ).check(app)

    @Test
    fun `composables get state and callbacks, not view models`() =
        // A screen gets its view models (a parameter for tests, or from its `…ViewModel()` factories) and hands its parts
        // what they show and what to call; its composable lambdas are part of it.
        noMethods()
            .that()
            .areAnnotatedWith(Composable::class.java)
            .and()
            .haveNameNotMatching(".*Screen|.*ViewModel|.*\\\$lambda\\$.*")
            .should(
                object : ArchCondition<JavaMethod>("take or get a view model") {
                    override fun check(
                        method: JavaMethod,
                        events: ConditionEvents,
                    ) {
                        fun JavaClass.isViewModel() = isAssignableTo(ViewModel::class.java)
                        method.rawParameterTypes.filter { it.isViewModel() }.forEach {
                            events.add(SimpleConditionEvent.satisfied(method, "${method.description} takes a ${it.simpleName}"))
                        }
                        method.methodCallsFromSelf.filter { it.target.rawReturnType.isViewModel() }.forEach {
                            events.add(SimpleConditionEvent.satisfied(method, it.description))
                        }
                    }
                },
            ).check(app)

    @Test
    fun `the terminal's receipt fields and print jobs are converted in one place each`() {
        noClasses()
            .that()
            .haveNameNotMatching(within(ReceiptLinesJson::class.java.name))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(ReceiptField::class.java)
            .check(app)
        noClasses()
            .that()
            .haveNameNotMatching(within(PrintRenderer::class.java.name))
            .should()
            .callConstructorWhere(target(owner(assignableTo(PrintJob::class.java).or(assignableTo(PrintLine::class.java)))))
            .check(app)
    }

    @Test
    fun `the app never logs`() {
        // Logs on a payment terminal can leak shared keys, SMTP passwords and card data.
        noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("android.util.Log")
            .check(app)
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(app)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(app)
    }

    private companion object {
        const val UI = "UI"
        const val PAYMENT = "Payment"
        const val EMAIL = "Email"
        const val RECEIPT = "Receipt"
        const val REFUND = "Refund"
        const val TERMINAL = "Terminal"
        const val DATA = "Data"

        const val CONTAINER = "io\\.minimpos\\.app\\.AppContainer\\b.*"
        const val DB = "io.minimpos.app.data.db.."
        const val TERMINAL_PACKAGE = "io.minimpos.app.terminal.."
        const val PAYMENT_STANDING_FILE = "io.minimpos.app.refund.PaymentStandingKt"
        const val TRANSACTION_ACTIONS_FILE = "io.minimpos.app.feature.TransactionActionsKt"
        const val OUTCOME_MESSAGES_FILE = "io.minimpos.app.feature.OutcomeMessagesKt"

        val UI_PACKAGES = arrayOf("io.minimpos.app.feature..", "io.minimpos.app.ui..", "io.minimpos.app.scan..", "io.minimpos.app.qr..")

        val BUSINESS_PACKAGES =
            arrayOf(
                "io.minimpos.app.data..",
                "io.minimpos.app.terminal..",
                "io.minimpos.app.receipt..",
                "io.minimpos.app.refund..",
                "io.minimpos.app.email..",
                "io.minimpos.app.payment..",
            )

        // Android unit tests compile to build/intermediates/…/debugUnitTest/…, which DO_NOT_INCLUDE_TESTS does not know.
        val app: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("io.minimpos.app")

        val pureDecisions: DescribedPredicate<JavaClass> =
            declaredIn("io.minimpos.app.payment", "Checkout.kt")
                .or(declaredIn("io.minimpos.app.refund", "PaymentStanding.kt"))
                .or(declaredIn("io.minimpos.app.refund", "RefundablePayment.kt"))
                .or(declaredIn("io.minimpos.app.feature.history", "HistorySearch.kt"))
                // TerminalSetup.kt also holds TerminalSetupSource, which reads the stored settings and secrets.
                .or(DescribedPredicate.describe("TerminalSetup") { it.name.matches(Regex(within(TerminalSetup::class.java.name))) })

        /** A name pattern for the classes [names] and those Kotlin nests in them (companions, lambdas, continuations). */
        fun within(vararg names: String) = names.joinToString("|", "(", ")(\\$.*)?") { Regex.escape(it) }

        /** Classes compiled from the Kotlin file [fileName] in [packageName]. */
        fun declaredIn(
            packageName: String,
            fileName: String,
        ): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe("declared in $fileName") { javaClass ->
                javaClass.packageName == packageName && javaClass.source.flatMap { it.fileName }.orElse(null) == fileName
            }

        /** A call to one of [methods] (or its default-arguments bridge) declared by the class named [owner]. */
        fun callTo(
            owner: String,
            vararg methods: String,
        ): DescribedPredicate<JavaMethodCall> =
            DescribedPredicate.describe("a call to $owner.${methods.joinToString("/")}") { call ->
                call.targetOwner.name == owner && call.name.removeSuffix("\$default") in methods
            }

        /** A call to one of [methods] (or its default-arguments bridge) of [type] or one of its subtypes. */
        fun callToSubtypeOf(
            type: Class<*>,
            vararg methods: String,
        ): DescribedPredicate<JavaMethodCall> =
            DescribedPredicate.describe("a call to ${type.simpleName}.${methods.joinToString("/")}") { call ->
                call.targetOwner.isAssignableTo(type) && call.name.removeSuffix("\$default") in methods
            }
    }
}
