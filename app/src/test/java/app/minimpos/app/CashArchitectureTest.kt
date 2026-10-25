package app.minimpos.app

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.repo.GiftCardRepository
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.payment.CashTenders
import app.minimpos.app.payment.RefundLegs
import app.minimpos.core.split.CashPayment
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

class CashArchitectureTest {
    @Test
    fun `cash moves only through its tender and return owners`() {
        val app =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("app.minimpos.app")
        rules.forEach { it.check(app) }
    }

    @Test
    fun `cash movement rejects recording cash elsewhere`() {
        val imported = ClassFileImporter().importClasses(CashMovementViolation::class.java)
        rules.forEach { assertTrue(it.description, it.evaluate(imported).hasViolation()) }
    }

    private class CashMovementViolation {
        suspend fun pay(sales: SaleRepository) = sales.recordCash("s", SaleEvent.CashTendered(CashPayment(1, 1), 0), null, null)

        suspend fun handBack(
            refunds: RefundRepository,
            refund: RefundEntity,
        ) = refunds.recordCashReturn(refund, null)

        suspend fun handOver(giftCards: GiftCardRepository) = giftCards.recordCashHandover("h")
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

        // Cash reaches neither Adyen nor a terminal, so its only safeguards are the stored balance, allocation and review
        // checks plus the confirmation and Manager approval its owners require; a second recording path would bypass them.
        val rules: List<ArchRule> =
            listOf(
                noClasses()
                    .that()
                    .haveNameNotMatching(except(CashTenders::class.java))
                    .should()
                    .callMethodWhere(callTo(SaleRepository::class.java, "recordCash", "createCash")),
                noClasses()
                    .that()
                    .haveNameNotMatching(except(RefundLegs::class.java))
                    .should()
                    .callMethodWhere(callTo(RefundRepository::class.java, "recordCashReturn")),
                noClasses()
                    .that()
                    .haveNameNotMatching(except(CashTenders::class.java))
                    .should()
                    .callMethodWhere(callTo(GiftCardRepository::class.java, "recordCashHandover")),
            )
    }
}
