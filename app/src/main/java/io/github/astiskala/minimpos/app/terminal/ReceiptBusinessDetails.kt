package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.terminal.transport.AdyenStoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment

/**
 * One store's proposed receipt fields; empty values preserve merchant text when [applyTo] is confirmed.
 * @property id Adyen store ID, used to distinguish stores with identical names.
 * @property reference Store reference, possibly blank.
 * @property name Store name used on Adyen shopper receipts, possibly blank.
 * @property address Newline-separated address components, possibly blank.
 * @property phone Store phone number, possibly blank.
 */
data class ReceiptBusiness(
    val id: String,
    val reference: String,
    val name: String,
    val address: String,
    val phone: String,
) {
    /** Whether the store supplies any fields that can be imported. */
    val available: Boolean get() = name.isNotBlank() || address.isNotBlank() || phone.isNotBlank()

    /** Replaces only nonblank business fields of [receipt]; tax ID, title, footer and other preferences are untouched. */
    fun applyTo(receipt: ReceiptSettings): ReceiptSettings =
        receipt.copy(
            businessName = name.ifBlank { receipt.businessName },
            addressLines = address.ifBlank { receipt.addressLines },
            phone = phone.ifBlank { receipt.phone },
        )
}

/** Result of the explicit receipt-business import lookup. */
sealed interface ReceiptBusinesses {
    /**
     * Stores available for review; empty when the account has none.
     * @property stores Proposals, not yet saved.
     */
    data class Listed(
        val stores: List<ReceiptBusiness>,
    ) : ReceiptBusinesses

    /**
     * Lookup needs setup first; no API call was made.
     * @property problem Missing account, key or detected environment, or simulator destination.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : ReceiptBusinesses

    /**
     * Adyen refused or the store list could not be read.
     * @property message Non-secret reason supplied by the integration.
     */
    data class Failed(
        val message: String,
    ) : ReceiptBusinesses
}

/**
 * Read-only store receipt lookup using the same unlocked account and environment as payments; never simulated.
 * @param setups Resolves setup and decrypts secrets once per lookup.
 * @param connect Builds the Management client; tests replace it with a fake.
 */
class ReceiptBusinessDetails(
    private val setups: TerminalSetupSource,
    private val connect: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment -> AdyenStoreDetails(key, environment) },
) {
    /** Loads account stores for manual selection and review, without writing settings or Adyen configuration. */
    suspend fun stores(): ReceiptBusinesses {
        val unlocked = setups.unlocked()
        val setup = unlocked.setup
        val merchant =
            setup.settings.terminal.merchantAccount
                .trim()
        val problem =
            when {
                setup.apiSetup == ApiSetup.Simulated -> SetupProblem.API_REQUIRED
                merchant.isBlank() -> SetupProblem.MERCHANT_ACCOUNT
                unlocked.apiKey == null -> setup.apiSetup.problem ?: SetupProblem.API_KEY
                setup.environment == null -> SetupProblem.ENVIRONMENT
                else -> null
            }
        if (problem != null) return ReceiptBusinesses.NotSetUp(problem)
        return when (val result = connect(checkNotNull(unlocked.apiKey), checkNotNull(setup.environment)).stores(merchant)) {
            is StoreListing.Failed -> {
                ReceiptBusinesses.Failed(result.message)
            }

            is StoreListing.Listed -> {
                ReceiptBusinesses.Listed(
                    result.stores.map {
                        ReceiptBusiness(it.id, it.reference, it.name, it.address, it.phone)
                    },
                )
            }
        }
    }
}
