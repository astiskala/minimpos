package app.minimpos.app

import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.UnreferencedRefundReason
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.RefundCancellations
import app.minimpos.app.payment.StoredPaymentActions
import app.minimpos.app.payment.UnreferencedRefundStart
import app.minimpos.terminal.checkout.PaymentModifications
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreferencedRefundArchitectureTest {
    @Test
    fun `unreferenced refunds and their cancellations start only in their owners`() {
        val app =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("app.minimpos.app")
        rules.forEach { it.check(app) }
    }

    @Test
    fun `starting or cancelling an unreferenced refund elsewhere is rejected`() {
        val imported = ClassFileImporter().importClasses(UnreferencedRefundViolation::class.java)
        rules.forEach { assertTrue(it.description, it.evaluate(imported).hasViolation()) }
    }

    private class UnreferencedRefundViolation {
        fun start(operations: FinancialOperations) =
            operations.unreferencedRefund(UnreferencedRefundStart(1, "EUR", "R-1", UnreferencedRefundReason.OTHER, "{}"))

        suspend fun cancel(modifications: PaymentModifications) = modifications.cancelPendingRefund("PSP", "void-1")

        suspend fun record(refunds: RefundRepository) {
            refunds.cancelSending("r", 0)
            refunds.cancelAnswered("r", RefundCancelStatus.REQUESTED, null, null)
        }
    }

    private companion object {
        fun callTo(
            type: Class<*>,
            vararg methods: String,
        ) = object : DescribedPredicate<JavaMethodCall>("a call to ${type.simpleName}.${methods.joinToString("/")}") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.isEquivalentTo(type) && call.name.substringBefore('$') in methods
        }

        fun except(owner: Class<*>) = "${Regex.escape(owner.name)}(\\$.*)?"

        // An unreferenced refund has no original payment to check, so the configuration recheck, frozen reason and
        // reference in StoredPaymentActions are its only start-time safeguards. Cancelling a delayed refund persists its
        // identity before sending; a second path could send a new request instead of recovering the first one.
        val rules: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(except(StoredPaymentActions::class.java))
                    .should()
                    .callMethodWhere(callTo(FinancialOperations::class.java, "unreferencedRefund")),
                noClasses()
                    .that()
                    .haveNameNotMatching(except(RefundCancellations::class.java))
                    .should()
                    .callMethodWhere(callTo(PaymentModifications::class.java, "cancelPendingRefund")),
                noClasses()
                    .that()
                    .haveNameNotMatching(except(RefundCancellations::class.java))
                    .should()
                    .callMethodWhere(callTo(RefundRepository::class.java, "cancelSending", "cancelAnswered")),
            )
    }
}
