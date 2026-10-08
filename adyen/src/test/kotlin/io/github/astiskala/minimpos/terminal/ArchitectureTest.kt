package io.github.astiskala.minimpos.terminal

import com.adyen.Client
import com.google.gson.JsonObject
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.astiskala.minimpos.terminal.client.Decline
import io.github.astiskala.minimpos.terminal.client.TransactionDetails
import io.github.astiskala.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import io.github.astiskala.minimpos.terminal.transport.AdyenCloudDevices
import io.github.astiskala.minimpos.terminal.transport.AdyenHttp
import io.github.astiskala.minimpos.terminal.transport.AdyenStoreDetails
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.AdyenWalletMethods
import io.github.astiskala.minimpos.terminal.transport.TerminalHttpClient
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
            .resideInAnyPackage("android..", "androidx..", "io.github.astiskala.minimpos.app..", "io.github.astiskala.minimpos.core..")
            .check(terminal)

    @Test
    fun `API clients use official models instead of manual Gson objects`() =
        apiModelOwnership(
            resideInAnyPackage("io.github.astiskala.minimpos.terminal.checkout..")
                .or(type(AdyenStoreDetails::class.java))
                .or(type(AdyenTerminalDetails::class.java))
                .or(type(AdyenWalletMethods::class.java))
                .or(type(AdyenCloudDevices::class.java))
                .or(type(AdyenPaymentsAppManagement::class.java)),
        ).check(terminal)

    @Test
    fun `API model ownership rejects a hand-written request`() {
        val violation = ClassFileImporter().importClasses(RawApiModelViolation::class.java)
        val rule = apiModelOwnership(type(RawApiModelViolation::class.java))
        assertTrue(rule.description, rule.evaluate(violation).hasViolation())
    }

    private class RawApiModelViolation {
        fun body() = JsonObject()
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
                "io.github.astiskala.minimpos.terminal.transport..",
                "io.github.astiskala.minimpos.terminal.checkout..",
                "io.github.astiskala.minimpos.terminal.paymentsapp..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage("okhttp3..", "okio..", "javax.net..")
            .check(terminal)

    @Test
    fun `only the transports send HTTP requests`() =
        // Calls to Adyen's HTTPS APIs go through AdyenHttp, which owns the retry, timeout and failure rules.
        noClasses()
            .that()
            .resideOutsideOfPackage("io.github.astiskala.minimpos.terminal.transport..")
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
            resideInAnyPackage("io.github.astiskala.minimpos.terminal.client..", "io.github.astiskala.minimpos.terminal.checkout.."),
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
    fun `packages only depend downwards`() =
        // The simulator stands in for both APIs and shares its ledger between them, so nothing relies on it; the Terminal
        // API client and the Checkout API client know nothing of each other. The Payments app is one more transport
        // (with its own boarding), which only the app plugs in.
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(SIMULATOR)
            .definedBy("io.github.astiskala.minimpos.terminal.simulator..")
            .layer(CLIENT)
            .definedBy("io.github.astiskala.minimpos.terminal.client..")
            .layer(CHECKOUT)
            .definedBy("io.github.astiskala.minimpos.terminal.checkout..")
            .layer(PAYMENTS_APP)
            .definedBy("io.github.astiskala.minimpos.terminal.paymentsapp..")
            .layer(TRANSPORT)
            .definedBy("io.github.astiskala.minimpos.terminal.transport..")
            .layer(PARSE)
            .definedBy("io.github.astiskala.minimpos.terminal.parse..")
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
                "io.github.astiskala.minimpos.terminal.simulator..",
                "io.github.astiskala.minimpos.terminal.client..",
                "io.github.astiskala.minimpos.terminal.checkout..",
                "io.github.astiskala.minimpos.terminal.paymentsapp..",
                "io.github.astiskala.minimpos.terminal.transport..",
                "io.github.astiskala.minimpos.terminal.parse..",
            ).check(terminal)

    @Test
    fun `packages have no dependency cycles`() =
        slices()
            .matching("io.github.astiskala.minimpos.terminal.(*)..")
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
                .resideInAnyPackage("com.google.gson..")

        fun deliveryOwnership(selected: DescribedPredicate<JavaClass>): ArchRule =
            noClasses().that(selected).should().dependOnClassesThat(assignableTo(IOException::class.java))

        const val SIMULATOR = "Simulator"
        const val CLIENT = "Client"
        const val CHECKOUT = "Checkout"
        const val PAYMENTS_APP = "PaymentsApp"
        const val TRANSPORT = "Transport"
        const val PARSE = "Parse"

        val terminal: JavaClasses =
            ClassFileImporter()
                .withImportOption(
                    ImportOption.Predefined.DO_NOT_INCLUDE_TESTS,
                ).importPackages("io.github.astiskala.minimpos.terminal")
    }
}
