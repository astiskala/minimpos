package app.minimpos.app

import app.minimpos.app.ArchitectureTest.Companion.OUTCOME_MESSAGES_FILE
import app.minimpos.app.ArchitectureTest.Companion.UI_PACKAGES
import app.minimpos.app.ArchitectureTest.Companion.app
import app.minimpos.app.ArchitectureTest.Companion.callToSubtypeOf
import app.minimpos.app.ArchitectureTest.Companion.declaredIn
import app.minimpos.app.ArchitectureTest.Companion.within
import app.minimpos.app.data.db.Failure
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Failures stay typed from where they happen to `OutcomeMessages.kt`, which alone words them. */
class FailureArchitectureTest {
    @Test
    fun `exception text is never shown or stored`() = exceptionText.check(app)

    @Test
    fun `exception text ownership rejects reading an exception's message`() = reject(exceptionText, ExceptionTextViolation::class.java)

    private class ExceptionTextViolation {
        fun reason(error: IOException) = error.message
    }

    @Test
    fun `only email and stored reasons create verbatim external text`() = externalTextOwnership.check(app)

    @Test
    fun `external text ownership rejects words the app wrote`() = reject(externalTextOwnership, ExternalTextViolation::class.java)

    private class ExternalTextViolation {
        fun said() = ExternalText("The app wrote this")
    }

    @Test
    fun `failures are worded in one place`() = failureWording.check(app)

    @Test
    fun `failure wording rejects reading a fault outside OutcomeMessages`() = reject(failureWording, FailureWordingViolation::class.java)

    private class FailureWordingViolation {
        fun fault(failure: Failure.Remote) = failure.fault
    }

    private fun reject(
        rule: ArchRule,
        violation: Class<*>,
    ) = assertTrue(rule.description, rule.evaluate(ClassFileImporter().importClasses(violation)).hasViolation())

    private companion object {
        // Exception text is English, unstable and may leak details, so failures are typed (Fault, DeviceFault,
        // EmailFault) instead. JavaMail keeps the mail server's own reply only as its SMTP exceptions' text, which
        // SmtpMailer.kt alone reads, verbatim, into ExternalText.
        val exceptionText: ArchRule =
            noClasses()
                .that(not(declaredIn("app.minimpos.app.email", "SmtpMailer.kt")))
                .should()
                .callMethodWhere(callToSubtypeOf(Throwable::class.java, "getMessage", "getLocalizedMessage"))

        // ExternalText marks words Adyen, a terminal, the Payments app or a mail server wrote, so screens never
        // translate it. Only email (the SMTP reply) and stored reasons (decoding what was stored) create it here;
        // :adyen's parsers create everything else.
        val externalTextOwnership: ArchRule =
            noClasses()
                .that(not(resideInAnyPackage("app.minimpos.app.email..", "app.minimpos.app.data.db..")))
                .should()
                .callMethodWhere(
                    DescribedPredicate.describe<JavaMethodCall>("a creation of ExternalText") { call ->
                        call.targetOwner.name in setOf(ExternalText::class.java.name, "${ExternalText::class.java.name}\$Companion") &&
                            (call.name == "constructor-impl" || call.name.substringBefore('-') == "of")
                    },
                )

        // Screens and view models carry failures whole; only OutcomeMessages.kt reads their parts (hosts, codes,
        // Adyen's words) to word them in the current language.
        val failureWording: ArchRule =
            noClasses()
                .that(
                    resideInAnyPackage(*UI_PACKAGES)
                        .and(not(nameMatching(within(OUTCOME_MESSAGES_FILE))))
                        .or(type(FailureWordingViolation::class.java)),
                ).should()
                .callMethodWhere(
                    DescribedPredicate.describe<JavaMethodCall>("a read of a failure's parts") { call ->
                        val owner = call.targetOwner
                        (owner.isAssignableTo(Failure::class.java) || owner.isAssignableTo(Fault::class.java)) &&
                            call.name.startsWith("get")
                    },
                )
    }
}
