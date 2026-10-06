package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.transport.AdyenStoreDetails
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing

/**
 * One store's proposed receipt fields; existing merchant text is never replaced by [applyTo].
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

    /** Fills only blank business fields of [receipt]; tax ID, title, footer and other preferences are untouched. */
    fun applyTo(receipt: ReceiptSettings): ReceiptSettings =
        receipt.copy(
            businessName = receipt.businessName.ifBlank { name.ifBlank { receipt.businessName } },
            addressLines = receipt.addressLines.ifBlank { address.ifBlank { receipt.addressLines } },
            phone = receipt.phone.ifBlank { phone.ifBlank { receipt.phone } },
        )
}

/** Result of the receipt-business import lookup; no lookup changes saved fields. */
sealed interface ReceiptBusinesses {
    /**
     * Stores available for review; empty when the account has none. An assigned store is offered alone.
     * @property stores Proposals, not yet saved.
     */
    data class Listed(
        val stores: List<ReceiptBusiness>,
    ) : ReceiptBusinesses

    /**
     * Lookup needs setup first or its originating account/store no longer matches.
     * @property problem Missing setup, simulator destination, context mismatch or inaccessible assigned store.
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
 * @param connect Builds the store Management client; tests replace it with a fake.
 * @param terminals Reads the selected terminal's current assignment so only an ambiguous store needs selection.
 */
class ReceiptBusinessDetails(
    private val setups: TerminalSetupSource,
    private val connect: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment -> AdyenStoreDetails(key, environment) },
    private val terminals: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
) {
    /** Loads the assigned store or account stores for review, without writing settings or Adyen configuration. */
    suspend fun stores(): ReceiptBusinesses = stores(setups.unlocked())

    internal suspend fun validateOrigin(origin: TerminalSettings): SetupProblem? =
        SetupProblem.SETUP_CHANGED.takeUnless { setups.current().settings.terminal == origin }

    internal suspend fun stores(unlocked: UnlockedSetup): ReceiptBusinesses {
        val problem = lookupProblem(unlocked)
        if (problem != null) return ReceiptBusinesses.NotSetUp(problem)
        return when (val scope = assignedStore(unlocked)) {
            is StoreScope.Blocked -> ReceiptBusinesses.NotSetUp(scope.problem)
            is StoreScope.Assigned -> proposals(unlocked, scope.id)
        }
    }

    private fun lookupProblem(unlocked: UnlockedSetup): SetupProblem? {
        val setup = unlocked.setup
        return when {
            setup.apiSetup == ApiSetup.Simulated -> SetupProblem.API_REQUIRED

            setup.settings.terminal.merchantAccount
                .isBlank() -> SetupProblem.MERCHANT_ACCOUNT

            unlocked.apiKey == null -> setup.apiSetup.problem ?: SetupProblem.API_KEY

            setup.environment == null -> setup.apiSetup.problem ?: SetupProblem.ENVIRONMENT

            else -> null
        }
    }

    private suspend fun assignedStore(unlocked: UnlockedSetup): StoreScope {
        val setup = unlocked.setup
        if (!setup.discoversTerminals || setup.poiId == null) return StoreScope.Assigned(setup.settings.terminal.storeId)
        return when (val listing = terminals(checkNotNull(unlocked.apiKey)).terminals(checkNotNull(setup.environment))) {
            is TerminalListing.Failed -> {
                StoreScope.Blocked(listing.reason.setupProblem())
            }

            is TerminalListing.Listed -> {
                val terminal = listing.terminals.firstOrNull { it.id == setup.poiId }
                when {
                    terminal == null -> {
                        StoreScope.Blocked(SetupProblem.TERMINAL_ACCESS)
                    }

                    terminal.merchantAccount !=
                        setup.settings.terminal.merchantAccount
                            .trim()
                    -> {
                        StoreScope.Blocked(
                            SetupProblem.MERCHANT_MISMATCH,
                        )
                    }

                    else -> {
                        StoreScope.Assigned(terminal.storeId)
                    }
                }
            }
        }
    }

    private suspend fun proposals(
        unlocked: UnlockedSetup,
        assigned: String,
    ): ReceiptBusinesses {
        val setup = unlocked.setup
        val merchant =
            setup.settings.terminal.merchantAccount
                .trim()
        return when (val listing = connect(checkNotNull(unlocked.apiKey), checkNotNull(setup.environment)).stores(merchant)) {
            is StoreListing.Failed -> {
                ReceiptBusinesses.Failed(listing.message)
            }

            is StoreListing.Listed -> {
                if (assigned.isNotBlank() && listing.stores.none { it.id == assigned }) {
                    ReceiptBusinesses.NotSetUp(SetupProblem.STORE_ACCESS)
                } else {
                    ReceiptBusinesses.Listed(
                        listing.stores.filter { assigned.isBlank() || it.id == assigned }.map {
                            ReceiptBusiness(it.id, it.reference, it.name, it.address, it.phone)
                        },
                    )
                }
            }
        }
    }

    private sealed interface StoreScope {
        class Assigned(
            val id: String,
        ) : StoreScope

        class Blocked(
            val problem: SetupProblem,
        ) : StoreScope
    }
}
