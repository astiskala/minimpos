package app.minimpos.app

import app.minimpos.app.payment.CheckoutRefunds
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.checkout.SavedCardApi
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCardArchitectureTest {
    @Test
    fun `saved cards are charged and Checkout refunds sent only by their owners`() {
        val app =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption { location -> !location.contains("UnitTest") }
                .importPackages("app.minimpos.app")
        ownership.check(app)
    }

    @Test
    fun `saved-card ownership rejects charges and refunds sent elsewhere`() {
        val imported = ClassFileImporter().importClasses(SavedCardViolation::class.java)
        assertTrue(ownership.description, ownership.evaluate(imported).hasViolation())
    }

    private class SavedCardViolation {
        suspend fun charge(
            api: SavedCardApi,
            request: CardChargeRequest,
        ) = api.charge(request, "key")

        suspend fun refund(api: PaymentModifications) = api.refund("PSP", ModificationAmount("AUD", 1), "R", "key")
    }

    private companion object {
        val owners = listOf(SavedCardCharges::class.java.name, CheckoutRefunds::class.java.name)

        fun callTo(
            type: Class<*>,
            method: String,
        ) = object : DescribedPredicate<JavaMethodCall>("a call to ${type.simpleName}.$method") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.isAssignableTo(type) && call.name.substringBefore('$') == method
        }

        // A saved-card charge or Checkout refund is stored PENDING first and sent from what was stored with its one
        // idempotency key, after Manager approval and context rechecks; sending it elsewhere could charge or refund
        // twice. The terminal package only wires the clients.
        val ownership: ArchRule =
            noClasses()
                .that()
                .haveNameNotMatching(owners.joinToString("|", "(", ")(\\$.*)?") { Regex.escape(it) })
                .and()
                .resideOutsideOfPackage("app.minimpos.app.terminal..")
                .should()
                .callMethodWhere(callTo(SavedCardApi::class.java, "charge"))
                .orShould()
                .callMethodWhere(callTo(PaymentModifications::class.java, "refund"))
    }
}
