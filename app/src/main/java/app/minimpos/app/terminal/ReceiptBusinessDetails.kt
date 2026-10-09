package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.transport.AdyenStoreDetails
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.MerchantLookup
import app.minimpos.terminal.transport.StoreDetailsApi
import app.minimpos.terminal.transport.StoreLookup
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing

/**
 * Proposed receipt fields from the assigned store or merchant account; existing merchant text is never replaced by
 * [applyTo].
 * @property name Store shopper receipt name or merchant legal name, possibly blank.
 * @property address Newline-separated address components, possibly blank.
 * @property phone Store phone number, possibly blank.
 */
data class ReceiptBusiness(
    val name: String,
    val address: String,
    val phone: String,
) {
    /** Whether the proposal supplies any fields that can be imported. */
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
     * Details of the assigned store, or of the merchant account when no store is assigned, for review.
     * @property business Proposal, not yet saved.
     * @property fromStore Whether a store supplied it; a merchant account has only a legal name, never an address or
     *   phone.
     */
    data class Found(
        val business: ReceiptBusiness,
        val fromStore: Boolean,
    ) : ReceiptBusinesses

    /**
     * Nothing could be proposed.
     * @property failure Why: missing setup, simulator destination, context mismatch or inaccessible assigned store
     *   ([Failure.NotSetUp]), or Adyen refused or the store or merchant details could not be read.
     */
    data class Failed(
        val failure: Failure,
    ) : ReceiptBusinesses
}

/**
 * Read-only store receipt lookup using the same unlocked account and environment as payments; never simulated.
 * @param setups Resolves setup and decrypts secrets once per lookup.
 * @param connect Builds the store Management client; tests replace it with a fake.
 * @param terminals Reads the selected terminal's current store or merchant-account assignment.
 */
class ReceiptBusinessDetails(
    private val setups: TerminalSetupSource,
    private val connect: (String, TerminalEnvironment) -> StoreDetailsApi = { key, environment -> AdyenStoreDetails(key, environment) },
    private val terminals: (String) -> TerminalDetailsApi = { AdyenTerminalDetails(it) },
) {
    /** Loads the assigned store, or the merchant legal name without unrelated stores; never writes settings or Adyen. */
    suspend fun lookup(): ReceiptBusinesses = lookup(setups.unlocked())

    internal suspend fun validateOrigin(origin: TerminalSettings): SetupProblem? =
        SetupProblem.SETUP_CHANGED.takeUnless { setups.current().settings.terminal == origin }

    internal suspend fun lookup(unlocked: UnlockedSetup): ReceiptBusinesses {
        val problem = lookupProblem(unlocked)
        if (problem != null) return ReceiptBusinesses.Failed(Failure.NotSetUp(problem))
        return when (val scope = assignedStore(unlocked)) {
            is StoreScope.Blocked -> ReceiptBusinesses.Failed(scope.failure)
            is StoreScope.Assigned -> proposal(unlocked, scope.id)
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
        return when (val listing = terminals(checkNotNull(unlocked.apiKey)).terminals(checkNotNull(setup.environment), setup.poiId)) {
            is TerminalListing.Failed -> {
                StoreScope.Blocked(Failure.Remote(listing.fault))
            }

            is TerminalListing.Listed -> {
                when (
                    val assignment =
                        TerminalAssignments.receipt(
                            listing.terminals,
                            setup.poiId,
                            setup.settings.terminal.merchantAccount,
                        )
                ) {
                    is TerminalAssignment.Assigned -> StoreScope.Assigned(assignment.terminal.storeId)
                    is TerminalAssignment.Blocked -> StoreScope.Blocked(Failure.NotSetUp(assignment.problem))
                }
            }
        }
    }

    private suspend fun proposal(
        unlocked: UnlockedSetup,
        assigned: String,
    ): ReceiptBusinesses {
        val setup = unlocked.setup
        val merchant =
            setup.settings.terminal.merchantAccount
                .trim()
        val api = connect(checkNotNull(unlocked.apiKey), checkNotNull(setup.environment))
        val store = assigned.trim()
        if (store.isEmpty()) {
            return when (val lookup = api.merchant(merchant)) {
                is MerchantLookup.Found -> ReceiptBusinesses.Found(ReceiptBusiness(lookup.legalName, "", ""), fromStore = false)
                is MerchantLookup.Failed -> ReceiptBusinesses.Failed(Failure.Remote(lookup.fault))
            }
        }
        return when (val lookup = api.store(merchant, store)) {
            is StoreLookup.Found -> {
                val details = lookup.store
                ReceiptBusinesses.Found(ReceiptBusiness(details.name, details.address, details.phone), fromStore = true)
            }

            StoreLookup.Missing -> {
                ReceiptBusinesses.Failed(Failure.NotSetUp(SetupProblem.STORE_ACCESS))
            }

            is StoreLookup.Failed -> {
                ReceiptBusinesses.Failed(Failure.Remote(lookup.fault))
            }
        }
    }

    private sealed interface StoreScope {
        class Assigned(
            val id: String,
        ) : StoreScope

        class Blocked(
            val failure: Failure,
        ) : StoreScope
    }
}
