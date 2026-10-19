package app.minimpos.app

import app.minimpos.app.ArchitectureTest.Companion.app
import app.minimpos.app.ArchitectureTest.Companion.callToSubtypeOf
import app.minimpos.app.ArchitectureTest.Companion.within
import app.minimpos.app.terminal.TapToPayDiagnostics
import app.minimpos.terminal.paymentsapp.PaymentsAppManagement
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

/** Advisory introspection has one owner and cannot acquire registration or destructive recovery responsibilities. */
class TapToPayArchitectureTest {
    // Only this owner can authenticate and interpret the optional diagnostic. In particular, ordinary payment sends
    // must not silently acquire a /me dependency, and permission failures cannot trigger token generation/revocation.
    private val introspection =
        noClasses()
            .that()
            .haveNameNotMatching(within(TapToPayDiagnostics::class.java.name))
            .should()
            .callMethodWhere(callToSubtypeOf(PaymentsAppManagement::class.java, "credential"))

    private val readOnly =
        noClasses()
            .that()
            .haveNameMatching(within(TapToPayDiagnostics::class.java.name, DiagnosticWriteViolation::class.java.name))
            .should()
            .callMethodWhere(callToSubtypeOf(PaymentsAppManagement::class.java, "boardingToken", "revoke"))

    // UI uses the shared connection test, not a second credential-check workflow. Bootstrap only wires its callback.
    private val sharedConnection =
        noClasses()
            .that()
            .resideOutsideOfPackage("app.minimpos.app.terminal..")
            .and()
            .haveNameNotMatching(within(AppContainer::class.java.name))
            .should()
            .callMethodWhere(callToSubtypeOf(TapToPayDiagnostics::class.java, "refresh"))

    @Test
    fun `only advisory diagnostics introspects the boarding credential and remains read only`() {
        introspection.check(app)
        readOnly.check(app)
        sharedConnection.check(app)
    }

    @Test
    fun `diagnostic ownership rejects a second introspection and revocation caller`() {
        assertTrue(introspection.evaluate(ClassFileImporter().importClasses(IntrospectionViolation::class.java)).hasViolation())
        assertTrue(readOnly.evaluate(ClassFileImporter().importClasses(DiagnosticWriteViolation::class.java)).hasViolation())
        assertTrue(sharedConnection.evaluate(ClassFileImporter().importClasses(SeparateCheckViolation::class.java)).hasViolation())
    }

    private class IntrospectionViolation {
        suspend fun inspect(api: PaymentsAppManagement) = api.credential()
    }

    private class DiagnosticWriteViolation {
        suspend fun revoke(api: PaymentsAppManagement) = api.revoke("Merchant", "Installation")
    }

    private class SeparateCheckViolation {
        suspend fun check(diagnostics: TapToPayDiagnostics) = diagnostics.refresh()
    }
}
