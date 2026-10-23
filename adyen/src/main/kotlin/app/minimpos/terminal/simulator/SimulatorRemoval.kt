package app.minimpos.terminal.simulator

/**
 * Durable history identities no longer recoverable or actionable by the application. Only delete these after commit.
 * Reconciliation period totals are deliberately independent and remain until period close.
 *
 * @property serviceIds Terminal request ServiceIDs whose status answers can be forgotten.
 * @property transactionIds POI transaction IDs, including earlier split tenders.
 * @property pspReferences Payment PSP references whose simulated modification state can be forgotten.
 * @property chargeKeys Saved-card idempotency keys whose stored operations have been deleted.
 */
data class SimulatorRemoval(
    val serviceIds: Set<String>,
    val transactionIds: Set<String>,
    val pspReferences: Set<String>,
    val chargeKeys: Set<String>,
)
