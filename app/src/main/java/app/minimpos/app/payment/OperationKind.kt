package app.minimpos.app.payment

/**
 * One runner of financial operations as [FinancialOperations] holds it: the one contract every runner fills the same
 * way, so [FinancialOperations.running] and history deletion iterate the runners instead of naming each one's flags.
 * Each runner makes its own; a runner built after [FinancialOperations] adds itself with [FinancialOperations.include].
 *
 * @param busy Whether an operation of this kind is being sent, checked or reconciled now, so the configuration it uses
 *   must not be replaced; unfinished history alone does not count.
 * @param release Releases the process-only requests of records history durably deleted, by record ID.
 * @param releaseAll Releases every process-only request after a confirmed complete history purge.
 */
class OperationKind internal constructor(
    private val busy: () -> Boolean,
    private val release: (Set<String>) -> Unit = {},
    private val releaseAll: () -> Unit = {},
) {
    /** Whether an operation of this kind is being sent, checked or reconciled now. */
    val running: Boolean get() = busy()

    /** Releases the process-only requests of [ids], records history durably deleted. */
    internal fun forget(ids: Set<String>) = release(ids)

    /** Releases every process-only request after a confirmed complete history purge. */
    internal fun forgetAll() = releaseAll()
}
