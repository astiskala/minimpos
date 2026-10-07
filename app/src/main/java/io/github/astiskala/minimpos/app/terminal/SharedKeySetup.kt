package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.StorageJson
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.SharedKeyUpdate
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/** Non-secret confirmation for one exact setup; never contains API keys or passphrases.
 * @property poiId Selected terminal ID.
 * @property environment Selected or certificate-verified environment.
 * @property resume Whether this context already has an encrypted pending creation record.
 * @property replacesLocalKey Whether creation will replace locally supplied key values.
 * @property terminal Connection fields at the time of the offer; changes invalidate consent.
 * @property credentialIdentity Non-secret hash of the main credential; changes invalidate consent.
 */
class SharedKeyOffer internal constructor(
    val poiId: String,
    val environment: TerminalEnvironment,
    val resume: Boolean,
    val replacesLocalKey: Boolean,
    internal val terminal: TerminalSettings,
    internal val credentialIdentity: String,
) {
    internal fun matches(other: SharedKeyOffer): Boolean =
        poiId == other.poiId && environment == other.environment && terminal == other.terminal &&
            credentialIdentity == other.credentialIdentity
}

internal sealed interface SharedKeySetupOutcome {
    class Confirmation(
        val offer: SharedKeyOffer,
    ) : SharedKeySetupOutcome

    class Ready(
        val key: DiscoveredKey,
        val pending: Boolean,
    ) : SharedKeySetupOutcome {
        val unverifiedProblem: SetupProblem get() = if (pending) SetupProblem.KEY_CONNECTION_PENDING else SetupProblem.SETUP_NOT_VERIFIED
    }

    data class Failed(
        val problem: SetupProblem? = null,
        val message: String? = null,
    ) : SharedKeySetupOutcome
}

/** Owns confirmed terminal-specific creation and encrypted recovery, independent of active setup/import commits.
 * Reads never create keys. Explicit retries reuse stored values; cancellation never rolls back remote configuration.
 * @param setups Owns credential access, cryptographic generation and encrypted journal storage.
 * @param connect Creates Management access in the selected environment.
 * @param verifyApi Checks roles, terminal assignment and Checkout access before offers and writes.
 * @param busy Whether a financial operation is actively running; unfinished history alone does not block creation.
 */
class SharedKeySetup internal constructor(
    private val setups: TerminalSetupSource,
    private val connect: (String) -> TerminalDetailsApi,
    private val verifyApi: suspend (UnlockedSetup) -> ApiCheck,
    private val busy: () -> Boolean,
) {
    private val mutex = Mutex()

    internal suspend fun resolve(
        unlocked: UnlockedSetup,
        confirmed: SharedKeyOffer? = null,
        unchanged: suspend () -> Boolean = { true },
    ): SharedKeySetupOutcome =
        mutex.withLock {
            check(unlocked, unchanged)?.let { return@withLock it }
            if (confirmed != null && !offer(unlocked, resume = false).matches(confirmed)) {
                return@withLock SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
            }
            val records = records() ?: return@withLock SharedKeySetupOutcome.Failed(SetupProblem.KEY_RECOVERY_UNREADABLE)
            read(Attempt(unlocked, records, connect(checkNotNull(unlocked.apiKey)), unchanged), confirmed)
        }

    private suspend fun check(
        unlocked: UnlockedSetup,
        unchanged: suspend () -> Boolean,
    ): SharedKeySetupOutcome.Failed? {
        val setup = unlocked.setup
        if (busy() || !unchanged()) return SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
        if (!setup.discoversTerminals || !setup.needsSharedKey) return SharedKeySetupOutcome.Failed(SetupProblem.KEY_IDENTIFIER)
        val problem = setup.connectionProblem?.takeUnless { it in KEY_PROBLEMS }
        if (problem != null) return SharedKeySetupOutcome.Failed(problem)
        return when (val checked = verifyApi(unlocked)) {
            is ApiCheck.NotSetUp -> SharedKeySetupOutcome.Failed(checked.problem)
            is ApiCheck.Failed -> SharedKeySetupOutcome.Failed(message = checked.message)
            ApiCheck.Works -> null
        }
    }

    private suspend fun read(
        attempt: Attempt,
        confirmed: SharedKeyOffer?,
    ): SharedKeySetupOutcome {
        val found = attempt.api.sharedKey(attempt.id, attempt.environment)
        if (busy() || !attempt.unchanged()) return SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
        return when (found) {
            is SharedKeyLookup.Found -> {
                SharedKeySetupOutcome.Ready(found.key, attempt.previous != null)
            }

            is SharedKeyLookup.Failed -> {
                SharedKeySetupOutcome.Failed(found.reason.setupProblem())
            }

            SharedKeyLookup.Missing -> {
                if (confirmed == null) {
                    SharedKeySetupOutcome.Confirmation(offer(attempt.unlocked, attempt.previous != null))
                } else {
                    create(attempt)
                }
            }
        }
    }

    private suspend fun create(attempt: Attempt): SharedKeySetupOutcome {
        val pending = attempt.previous ?: newRecord(attempt.unlocked)
        save(attempt.records, pending)
        return check(attempt.unlocked, attempt.unchanged) ?: assignment(attempt) ?: patch(attempt, pending)
    }

    private suspend fun assignment(attempt: Attempt): SharedKeySetupOutcome.Failed? =
        when (val listing = attempt.api.terminals(attempt.environment, attempt.id)) {
            is TerminalListing.Failed -> {
                SharedKeySetupOutcome.Failed(listing.reason.setupProblem())
            }

            is TerminalListing.Listed -> {
                TerminalAssignments
                    .unchanged(listing.terminals, attempt.id, attempt.unlocked.setup.settings.terminal)
                    ?.let { SharedKeySetupOutcome.Failed(it) }
            }
        }

    private suspend fun patch(
        attempt: Attempt,
        pending: KeyRecord,
    ): SharedKeySetupOutcome {
        if (busy() || !attempt.unchanged()) return SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
        return when (val updated = attempt.api.createSharedKey(attempt.id, attempt.environment, pending.key())) {
            is SharedKeyUpdate.Failed -> {
                val problem = if (updated.uncertain) SetupProblem.KEY_CREATION_UNCONFIRMED else updated.reason.setupProblem()
                SharedKeySetupOutcome.Failed(problem)
            }

            is SharedKeyUpdate.Ready -> {
                if (attempt.unchanged()) {
                    SharedKeySetupOutcome.Ready(updated.key, pending = true)
                } else {
                    SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED)
                }
            }
        }
    }

    private class Attempt(
        val unlocked: UnlockedSetup,
        val records: KeyRecords,
        val api: TerminalDetailsApi,
        val unchanged: suspend () -> Boolean,
    ) {
        val previous = records.entries.firstOrNull { it.matches(unlocked) }
        val id: String get() = checkNotNull(unlocked.setup.poiId)
        val environment: TerminalEnvironment get() = checkNotNull(unlocked.setup.environment)
    }

    internal suspend fun pendingOffer(unlocked: UnlockedSetup): SharedKeyOffer? =
        mutex.withLock {
            if (unlocked.apiKey == null || unlocked.setup.poiId == null || unlocked.setup.environment == null) return@withLock null
            if (!unlocked.setup.discoversTerminals || !unlocked.setup.needsSharedKey) return@withLock null
            if (records()?.entries?.any { it.matches(unlocked) } == true) offer(unlocked, resume = true) else null
        }

    internal suspend fun complete(unlocked: UnlockedSetup) =
        mutex.withLock {
            if (!unlocked.setup.discoversTerminals || !unlocked.setup.needsSharedKey) return@withLock
            val current = records() ?: return@withLock
            val remaining = current.entries.filterNot { it.matches(unlocked) }
            if (remaining.size != current.entries.size) write(KeyRecords(remaining))
        }

    private fun offer(
        unlocked: UnlockedSetup,
        resume: Boolean,
    ): SharedKeyOffer =
        SharedKeyOffer(
            checkNotNull(unlocked.setup.poiId),
            checkNotNull(unlocked.setup.environment),
            resume,
            unlocked.setup.settings.terminal.keyIdentifier
                .isNotBlank() || unlocked.hasSharedPassphrase,
            unlocked.setup.settings.terminal,
            setups.keyRecovery.identity(checkNotNull(unlocked.apiKey)),
        )

    private fun newRecord(unlocked: UnlockedSetup): KeyRecord {
        val material = setups.keyRecovery.generate()
        return KeyRecord(
            checkNotNull(unlocked.apiKey),
            checkNotNull(unlocked.setup.environment),
            checkNotNull(unlocked.setup.poiId),
            unlocked.setup.settings.terminal.merchantAccount
                .trim(),
            material.identifier,
            material.passphrase,
            1,
        )
    }

    private suspend fun records(): KeyRecords? {
        val text = setups.keyRecovery.read()
        if (text == null) return if (setups.keyRecovery.exists()) null else KeyRecords()
        return try {
            StorageJson.decodeFromString(serializer<KeyRecords>(), text).takeIf { parsed ->
                parsed.entries.isNotEmpty() && parsed.entries.all { it.valid() }
            }
        } catch (ignored: SerializationException) {
            null
        }
    }

    private suspend fun save(
        records: KeyRecords,
        entry: KeyRecord,
    ) = write(
        KeyRecords(records.entries.filterNot { it.sameContext(entry) } + entry),
    )

    private suspend fun write(records: KeyRecords) =
        setups.keyRecovery.write(
            records.takeIf { it.entries.isNotEmpty() }?.let { StorageJson.encodeToString(serializer<KeyRecords>(), it) },
        )

    @Serializable
    private class KeyRecords(
        val entries: List<KeyRecord> = emptyList(),
    )

    @Serializable
    private class KeyRecord(
        val apiKey: String,
        val environment: TerminalEnvironment,
        val poiId: String,
        val merchant: String,
        val identifier: String,
        val passphrase: String,
        val version: Int,
    ) {
        fun valid(): Boolean = listOf(apiKey, poiId, merchant, identifier, passphrase).all(String::isNotBlank) && version == 1

        fun key(): DiscoveredKey = DiscoveredKey(identifier, version, passphrase)

        fun matches(unlocked: UnlockedSetup): Boolean =
            apiKey == unlocked.apiKey && environment == unlocked.setup.environment &&
                poiId == unlocked.setup.poiId && merchant ==
                unlocked.setup.settings.terminal.merchantAccount
                    .trim()

        fun sameContext(other: KeyRecord): Boolean =
            apiKey == other.apiKey && environment == other.environment && poiId == other.poiId && merchant == other.merchant
    }

    private companion object {
        val KEY_PROBLEMS =
            setOf(SetupProblem.KEY_IDENTIFIER, SetupProblem.KEY_VERSION, SetupProblem.PASSPHRASE, SetupProblem.UNREADABLE_PASSPHRASE)
    }
}
