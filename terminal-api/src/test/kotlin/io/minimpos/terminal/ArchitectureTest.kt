package io.minimpos.terminal

import com.adyen.Client
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
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
    fun `the simulator is a transport, not something the client relies on`() =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.minimpos.terminal.simulator..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.minimpos.terminal.simulator..")
            .check(terminal)

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
        val terminal: JavaClasses =
            ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.minimpos.terminal")
    }
}
