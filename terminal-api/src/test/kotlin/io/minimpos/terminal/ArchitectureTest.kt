package io.minimpos.terminal

import com.adyen.Client
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.Test

/** :terminal-api wraps the Adyen Java library for the local Terminal API; it runs on Android but must not depend on it. */
class ArchitectureTest {
    @Test
    fun `terminal-api is plain Kotlin and knows nothing of the app`() =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("android..", "androidx..", "io.minimpos.app..", "io.minimpos.core..")
            .check(terminal)

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
    fun `only the transport and the Checkout API client talk HTTP and TLS`() =
        noClasses()
            .that()
            .resideOutsideOfPackages("io.minimpos.terminal.transport..", "io.minimpos.terminal.checkout..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("okhttp3..", "okio..", "javax.net..")
            .check(terminal)

    @Test
    fun `packages only depend downwards`() =
        // The simulator stands in for both APIs and shares its ledger between them, so nothing relies on it; the Terminal
        // API client and the Checkout API client know nothing of each other.
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(SIMULATOR)
            .definedBy("io.minimpos.terminal.simulator..")
            .layer(CLIENT)
            .definedBy("io.minimpos.terminal.client..")
            .layer(CHECKOUT)
            .definedBy("io.minimpos.terminal.checkout..")
            .layer(TRANSPORT)
            .definedBy("io.minimpos.terminal.transport..")
            .layer(PARSE)
            .definedBy("io.minimpos.terminal.parse..")
            .whereLayer(SIMULATOR)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(CLIENT)
            .mayOnlyBeAccessedByLayers(SIMULATOR)
            .whereLayer(CHECKOUT)
            .mayOnlyBeAccessedByLayers(SIMULATOR)
            .whereLayer(TRANSPORT)
            .mayOnlyBeAccessedByLayers(CLIENT, CHECKOUT, SIMULATOR)
            .whereLayer(PARSE)
            .mayOnlyBeAccessedByLayers(TRANSPORT, CLIENT, SIMULATOR)
            .check(terminal)

    @Test
    fun `every class is in a layer`() =
        classes()
            .should()
            .resideInAnyPackage(
                "io.minimpos.terminal.simulator..",
                "io.minimpos.terminal.client..",
                "io.minimpos.terminal.checkout..",
                "io.minimpos.terminal.transport..",
                "io.minimpos.terminal.parse..",
            ).check(terminal)

    @Test
    fun `packages have no dependency cycles`() =
        slices()
            .matching("io.minimpos.terminal.(*)..")
            .should()
            .beFreeOfCycles()
            .check(terminal)

    @Test
    fun `terminal-api never logs or prints`() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(terminal)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(terminal)
    }

    private companion object {
        const val SIMULATOR = "Simulator"
        const val CLIENT = "Client"
        const val CHECKOUT = "Checkout"
        const val TRANSPORT = "Transport"
        const val PARSE = "Parse"

        val terminal: JavaClasses =
            ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.minimpos.terminal")
    }
}
