package app.minimpos.terminal

import app.minimpos.terminal.checkout.AdyenCheckout
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.client.Decline
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.transport.AdyenCloudDevices
import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenStoreDetails
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.AdyenWalletMethods
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.TerminalHttpClient
import com.adyen.Client
import com.adyen.model.management.DefaultErrorResponseEntity
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.gson.JsonObject
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasOwner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** :adyen stays Android-free and uses official wire models to prevent hand-written API fields from drifting. */
class ArchitectureTest {
    @Test
    fun `adyen is plain Kotlin and knows nothing of the app`() =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("android..", "androidx..", "app.minimpos.app..", "app.minimpos.core..")
            .check(terminal)

    @Test
    fun `API clients use official models instead of manual JSON trees`() =
        apiModelOwnership(
            resideInAnyPackage("app.minimpos.terminal.checkout..")
                .or(type(AdyenStoreDetails::class.java))
                .or(type(AdyenTerminalDetails::class.java))
                .or(type(AdyenWalletMethods::class.java))
                .or(type(AdyenCloudDevices::class.java))
                .or(type(AdyenPaymentsAppManagement::class.java)),
        ).check(terminal)

    @Test
    fun `API model ownership rejects a hand-written request`() {
        listOf(RawApiModelViolation::class.java, JacksonApiModelViolation::class.java).forEach { type ->
            val violation = ClassFileImporter().importClasses(type)
            val rule = apiModelOwnership(type(type))
            assertTrue(rule.description, rule.evaluate(violation).hasViolation())
        }
    }

    private class RawApiModelViolation {
        fun body() = JsonObject()
    }

    private class JacksonApiModelViolation {
        fun body(): ObjectNode = JsonNodeFactory.instance.objectNode()
    }

    @Test
    fun `the Adyen library's Apache HTTP client is never used`() {
        // It crashes on Android: every Client gets our OkHttp-based TerminalHttpClient instead.
        noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.adyen.httpclient.AdyenHttpClient")
            .orShould()
            .dependOnClassesThat()
            .resideInAnyPackage("org.apache.hc..", "org.apache.http..")
            .check(terminal)
        // Client.getHttpClient() creates that client on first use.
        noClasses().should().callMethod(Client::class.java, "getHttpClient").check(terminal)
    }

    @Test
    fun `the library's unencrypted TEST-only API is never used`() =
        noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.adyen.service.TerminalLocalAPIUnencrypted")
            .check(terminal)

    @Test
    fun `only the transports and the Checkout and Management API clients talk HTTP and TLS`() =
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "app.minimpos.terminal.transport..",
                "app.minimpos.terminal.checkout..",
                "app.minimpos.terminal.paymentsapp..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage("okhttp3..", "okio..", "javax.net..")
            .check(terminal)

    @Test
    fun `only the transports send HTTP requests`() =
        // Calls to Adyen's HTTPS APIs go through AdyenHttp, which owns the retry, timeout and failure rules.
        noClasses()
            .that()
            .resideOutsideOfPackage("app.minimpos.terminal.transport..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("okhttp3.Request")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("okhttp3.Call")
            .check(terminal)

    @Test
    fun `only AdyenHttp and the library's HTTP client make HTTP calls`() =
        // AdyenHttp makes the calls to Adyen's HTTPS APIs (cloud, Checkout, Management); TerminalHttpClient is the Adyen
        // library's client for the local Terminal API.
        noClasses()
            .that()
            .doNotBelongToAnyOf(AdyenHttp::class.java, TerminalHttpClient::class.java)
            .should()
            .callMethod(OkHttpClient::class.java, "newCall", Request::class.java)
            .check(terminal)

    @Test
    fun `transport failures reach the clients only as deliveries`() =
        // Each transport works out once whether a request can have taken effect (Delivery); the clients never catch.
        deliveryOwnership(
            resideInAnyPackage("app.minimpos.terminal.client..", "app.minimpos.terminal.checkout.."),
        ).check(terminal)

    @Test
    fun `delivery ownership rejects standard IO exceptions as well as transport subclasses`() {
        val violation = ClassFileImporter().importClasses(DeliveryViolation::class.java)
        val rule = deliveryOwnership(type(DeliveryViolation::class.java))
        assertTrue(rule.description, rule.evaluate(violation).hasViolation())
    }

    private class DeliveryViolation {
        fun exception() = IOException("not a delivery")
    }

    @Test
    fun `results carry faults and verbatim text, never exception text or English sentences`() {
        // The app words every Fault in the user's language and shows ExternalText as received, so nothing here reads
        // an exception's message or exposes a String reason or message on a public result.
        exceptionText(resideInAnyPackage("app.minimpos.terminal..")).check(terminal)
        sentenceResults(resideInAnyPackage("app.minimpos.terminal..")).check(terminal)
    }

    @Test
    fun `fault ownership rejects exception text and String reasons`() {
        val violation = ClassFileImporter().importClasses(SentenceViolation::class.java)
        listOf(exceptionText(type(SentenceViolation::class.java)), sentenceResults(type(SentenceViolation::class.java))).forEach {
            assertTrue(it.description, it.evaluate(violation).hasViolation())
        }
    }

    private class SentenceViolation(
        val reason: String,
    ) {
        fun said(error: IOException) = error.message
    }

    @Test
    fun `production code outside the simulator only constructs ExternalText through ExternalText of`() {
        externalTextOwnership(
            resideInAnyPackage("app.minimpos.terminal..")
                .and(DescribedPredicate.not(resideInAnyPackage("app.minimpos.terminal.simulator..")))
                .and(
                    DescribedPredicate.not(
                        type(ExternalText::class.java).or(type(ExternalText.Companion::class.java)),
                    ),
                ),
        ).check(terminal)
    }

    @Test
    fun `external text ownership rejects direct ExternalText construction`() {
        val violation = ClassFileImporter().importClasses(ExternalTextViolation::class.java)
        val rule = externalTextOwnership(type(ExternalTextViolation::class.java))
        assertTrue(rule.description, rule.evaluate(violation).hasViolation())
    }

    private class ExternalTextViolation {
        fun fabricated() = ExternalText("Cancelled by shopper")
    }

    @Test
    fun `only ManagementJson maps Management API error responses into faults`() =
        managementFaultOwnership(
            resideInAnyPackage("app.minimpos.terminal..")
                .and(DescribedPredicate.not(DescribedPredicate.describe("ManagementJson") { it.name.endsWith(".ManagementJsonKt") })),
        ).check(terminal)

    @Test
    fun `management fault ownership rejects direct error decoding in adapters`() {
        val violation = ClassFileImporter().importClasses(ManagementFaultViolation::class.java)
        val rule = managementFaultOwnership(type(ManagementFaultViolation::class.java))
        assertTrue(rule.description, rule.evaluate(violation).hasViolation())
    }

    private class ManagementFaultViolation {
        fun error() = DefaultErrorResponseEntity()
    }

    @Test
    fun `the Checkout API's parts are made together for one credential`() =
        // AdyenCheckout makes modifications, links and saved cards with one HTTP client and application information.
        // A part's default-argument constructor delegates to its own full constructor.
        checkoutParts(
            DescribedPredicate.not(
                JavaClass.Predicates.belongToAnyOf(
                    AdyenCheckout::class.java,
                    CheckoutModifications::class.java,
                    CheckoutPaymentLinks::class.java,
                    CheckoutSavedCards::class.java,
                ),
            ),
        ).check(terminal)

    @Test
    fun `checkout part ownership rejects a part made on its own`() {
        val violation = ClassFileImporter().importClasses(CheckoutPartViolation::class.java)
        val rule = checkoutParts(type(CheckoutPartViolation::class.java))
        assertTrue(rule.description, rule.evaluate(violation).hasViolation())
    }

    private class CheckoutPartViolation {
        fun make(credentials: CheckoutCredentials) = CheckoutPaymentLinks(credentials)
    }

    private fun checkoutParts(classes: DescribedPredicate<in JavaClass>): ArchRule =
        noClasses()
            .that(classes)
            .should()
            .callConstructorWhere(
                JavaCall.Predicates.target(
                    HasOwner.Predicates.With.owner(
                        JavaClass.Predicates.belongToAnyOf(
                            CheckoutModifications::class.java,
                            CheckoutPaymentLinks::class.java,
                            CheckoutSavedCards::class.java,
                        ),
                    ),
                ),
            )

    @Test
    fun `only the decline reads why a transaction was not approved`() =
        // Cancellations, busy terminals and retry advice come from Decline; nothing else compares ErrorCondition strings.
        noClasses()
            .that()
            .doNotBelongToAnyOf(Decline::class.java, TransactionDetails::class.java)
            .should()
            .callMethod(TransactionDetails::class.java, "getErrorCondition")
            .orShould()
            .callMethod(TransactionDetails::class.java, "getRefusalReason")
            .check(terminal)

    @Test
    fun `simple nexo answers and shopper display steps stay behind their single owners`() {
        terminalAnswerOwnership(
            resideInAnyPackage("app.minimpos.terminal.client..")
                .and(
                    DescribedPredicate.not(
                        DescribedPredicate.describe("TerminalAnswer or TerminalClient.execute") {
                            it.name.endsWith(".TerminalAnswerKt") || it.name == TerminalClient::class.java.name
                        },
                    ),
                ),
        ).check(terminal)
        shopperDisplayEncapsulation(
            DescribedPredicate.describe("ShopperDisplayKt") { it.name.endsWith(".ShopperDisplayKt") },
        ).check(terminal)
    }

    @Test
    fun `terminal answer and shopper display ownership reject direct envelope reads and public step helpers`() {
        val answerViolation = ClassFileImporter().importClasses(DirectEnvelopeReadViolation::class.java)
        assertTrue(
            terminalAnswerOwnership(type(DirectEnvelopeReadViolation::class.java))
                .evaluate(answerViolation)
                .hasViolation(),
        )
        val displayViolation = ClassFileImporter().importClasses(PublicShopperStepViolation::class.java)
        assertTrue(
            shopperDisplayEncapsulation(type(PublicShopperStepViolation::class.java))
                .evaluate(displayViolation)
                .hasViolation(),
        )
    }

    private class DirectEnvelopeReadViolation {
        fun unwrap(answered: Delivery.Answered) = answered.response
    }

    private class PublicShopperStepViolation {
        fun step(id: String) = id.trim()
    }

    @Test
    fun `packages only depend downwards`() =
        // The simulator stands in for both APIs and shares its ledger between them, so nothing relies on it; the Terminal
        // API client and the Checkout API client know nothing of each other. The Payments app is one more transport
        // (with its own boarding), which only the app plugs in.
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(SIMULATOR)
            .definedBy("app.minimpos.terminal.simulator..")
            .layer(CLIENT)
            .definedBy("app.minimpos.terminal.client..")
            .layer(CHECKOUT)
            .definedBy("app.minimpos.terminal.checkout..")
            .layer(PAYMENTS_APP)
            .definedBy("app.minimpos.terminal.paymentsapp..")
            .layer(TRANSPORT)
            .definedBy("app.minimpos.terminal.transport..")
            .layer(PARSE)
            .definedBy("app.minimpos.terminal.parse..")
            .whereLayer(SIMULATOR)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(CLIENT)
            .mayOnlyBeAccessedByLayers(SIMULATOR)
            .whereLayer(CHECKOUT)
            .mayOnlyBeAccessedByLayers(SIMULATOR)
            .whereLayer(PAYMENTS_APP)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(TRANSPORT)
            .mayOnlyBeAccessedByLayers(CLIENT, CHECKOUT, PAYMENTS_APP, SIMULATOR)
            .whereLayer(PARSE)
            .mayOnlyBeAccessedByLayers(TRANSPORT, CLIENT, PAYMENTS_APP, SIMULATOR)
            .check(terminal)

    @Test
    fun `every class is in a layer`() =
        classes()
            .should()
            .resideInAnyPackage(
                "app.minimpos.terminal.simulator..",
                "app.minimpos.terminal.client..",
                "app.minimpos.terminal.checkout..",
                "app.minimpos.terminal.paymentsapp..",
                "app.minimpos.terminal.transport..",
                "app.minimpos.terminal.parse..",
            ).check(terminal)

    @Test
    fun `packages have no dependency cycles`() =
        slices()
            .matching("app.minimpos.terminal.(*)..")
            .should()
            .beFreeOfCycles()
            .check(terminal)

    @Test
    fun `adyen never logs or prints`() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(terminal)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(terminal)
    }

    private companion object {
        fun apiModelOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses()
                .that(selected)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "com.google.gson..",
                    "com.fasterxml.jackson.databind.node..",
                    "kotlinx.serialization.json..",
                    "org.json..",
                ).because("Typed API adapters must serialize official SDK models, keeping wire fields owned by Adyen")

        fun deliveryOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses().that(selected).should().dependOnClassesThat(assignableTo(IOException::class.java))

        fun externalTextOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses()
                .that(selected)
                .should()
                .callMethod(ExternalText::class.java, "constructor-impl", String::class.java)
                .because("Production code outside the simulator wraps nullable remote fields via ExternalText.of")

        fun managementFaultOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses()
                .that(selected)
                .should()
                .dependOnClassesThat()
                .haveSimpleName("DefaultErrorResponseEntity")
                .because("Management adapters share error-response decoding and Fault mapping in ManagementJson")

        fun terminalAnswerOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses()
                .that(selected)
                .should()
                .callMethod(Delivery.Answered::class.java, "getResponse")
                .because("One-request nexo operations read response envelopes through readTerminalAnswer")

        fun shopperDisplayEncapsulation(selected: DescribedPredicate<JavaClass>): ArchRule =
            noMethods()
                .that()
                .areDeclaredInClassesThat(selected)
                .and()
                .doNotHaveName("executeShopperFlow")
                .and()
                .haveNameNotMatching("access\\$.*")
                .should()
                .bePublic()
                .allowEmptyShould(true)
                .because("Shopper display and Adyen Giving nexo steps sit behind TerminalClient.pay")

        const val SIMULATOR = "Simulator"
        const val CLIENT = "Client"
        const val CHECKOUT = "Checkout"
        const val PAYMENTS_APP = "PaymentsApp"
        const val TRANSPORT = "Transport"
        const val PARSE = "Parse"

        fun exceptionText(classes: DescribedPredicate<in JavaClass>): ArchRule =
            noClasses()
                .that(classes)
                .should()
                .callMethodWhere(
                    DescribedPredicate.describe("a read of an exception's message") { call ->
                        call.targetOwner.isAssignableTo(Throwable::class.java) && call.name in setOf("getMessage", "getLocalizedMessage")
                    },
                )

        fun sentenceResults(classes: DescribedPredicate<in JavaClass>): ArchRule =
            noMethods()
                .that()
                .areDeclaredInClassesThat(classes)
                .and()
                .arePublic()
                .and()
                .haveNameMatching("get(Reason|Message)")
                .should()
                .haveRawReturnType(String::class.java)
                // None remain, so only the deliberate violation exercises it.
                .allowEmptyShould(true)

        val terminal: JavaClasses =
            ClassFileImporter()
                .withImportOption(
                    ImportOption.Predefined.DO_NOT_INCLUDE_TESTS,
                ).importPackages("app.minimpos.terminal")
    }
}
