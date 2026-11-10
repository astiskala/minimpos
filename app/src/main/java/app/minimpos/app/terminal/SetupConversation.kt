package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRolesApi
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.MerchantAccountListing
import app.minimpos.terminal.transport.SharedKeyApi
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalEnvironment

/**
 * A question setup discovery asks the operator before it can go on, the same for the saved setup in
 * Settings › Terminal and for a scanned import candidate.
 */
sealed interface SetupQuestion {
    /**
     * Which terminal to set up: several are eligible.
     * @property terminals The choices, the most recently active first.
     */
    data class Terminals(
        val terminals: List<SetupTerminalChoice>,
    ) : SetupQuestion

    /**
     * Whether to take the merchant account the chosen terminal is assigned to instead of the one entered.
     * @property poiId The terminal that was chosen.
     * @property mismatch The entered and the assigned account.
     */
    data class AccountDiffers(
        val poiId: String,
        val mismatch: AccountMismatch,
    ) : SetupQuestion

    /**
     * Whether to give the API key the roles it lacks.
     * @property offer The roles to add.
     */
    data class RolesMissing(
        val offer: KeyRolesOffer,
    ) : SetupQuestion

    /**
     * Which merchant account Tap to Pay is set up for: the API key reaches several.
     * @property accounts The choices, in the order Adyen lists them.
     */
    data class Merchants(
        val accounts: List<SetupMerchantChoice>,
    ) : SetupQuestion

    /**
     * Whether to create (or finish creating) the terminal's shared key.
     * @property offer Non-secret consent for exactly this setup; a changed setup invalidates it.
     */
    data class KeyConfirmation(
        val offer: SharedKeyOffer,
    ) : SetupQuestion
}

/**
 * What the operator answered so far, carried into the next attempt. An answer applies only to what it was given for:
 * an accepted account to the terminal assigned to it, accepted roles while the key lacks exactly them, and an accepted
 * key while its offer still matches the setup.
 *
 * @property terminalId The terminal chosen among [SetupQuestion.Terminals]; null before choosing.
 * @property acceptedAccount The assigned account accepted for [SetupQuestion.AccountDiffers]; null before accepting.
 * @property acceptedRoles The roles accepted for [SetupQuestion.RolesMissing]; null before accepting.
 * @property merchantAccount The merchant account chosen among [SetupQuestion.Merchants]; null before choosing.
 * @property acceptedKey The key creation accepted for [SetupQuestion.KeyConfirmation]; null before accepting.
 */
data class SetupAnswers(
    val terminalId: String? = null,
    val acceptedAccount: String? = null,
    val acceptedRoles: KeyRolesOffer? = null,
    val merchantAccount: String? = null,
    val acceptedKey: SharedKeyOffer? = null,
) {
    /** These answers with [question] accepted; a choice among several is answered with [choose] instead. */
    fun accept(question: SetupQuestion): SetupAnswers =
        when (question) {
            is SetupQuestion.AccountDiffers -> copy(acceptedAccount = question.mismatch.assigned)
            is SetupQuestion.RolesMissing -> copy(acceptedRoles = question.offer)
            is SetupQuestion.KeyConfirmation -> copy(acceptedKey = question.offer)
            is SetupQuestion.Terminals, is SetupQuestion.Merchants -> this
        }

    /** These answers with [id] chosen among [question]'s choices; null when [question] did not offer it. */
    fun choose(
        question: SetupQuestion,
        id: String,
    ): SetupAnswers? =
        when (question) {
            is SetupQuestion.Terminals -> if (question.terminals.any { it.id == id }) copy(terminalId = id) else null
            is SetupQuestion.Merchants -> if (question.accounts.any { it.id == id }) copy(merchantAccount = id) else null
            is SetupQuestion.AccountDiffers, is SetupQuestion.RolesMissing, is SetupQuestion.KeyConfirmation -> null
        }
}

/** How far one of [SetupConversation]'s questions got. */
internal sealed interface SetupReply<out T> {
    /** Answered; [value] is what it established. */
    data class Next<out T>(
        val value: T,
    ) : SetupReply<T>

    /** Did not go on; [failure] is why, also for a subject that presents a question as a failure instead of asking it. */
    sealed interface Halted : SetupReply<Nothing> {
        val failure: Failure
    }

    /** The operator answers [question] first; until then setup cannot go on, as [failure] says. */
    data class Asks(
        val question: SetupQuestion,
        override val failure: Failure,
    ) : Halted

    /** It cannot go on, as [failure] says; [keyUnread] when only a shared key could not be read, which the helper can supply. */
    data class Failed(
        override val failure: Failure,
        val keyUnread: Boolean = false,
    ) : Halted
}

/**
 * Setup discovery's questions about what Adyen holds for a setup, asked one way for the saved setup
 * ([SetupDiscovery]) and for an import candidate ([SetupImport]): the API key's roles, the terminal and the account it
 * is assigned to, how that assignment fills the connection fields, the terminal's or Tap to Pay account's shared key,
 * and creating a missing key. It adds roles or creates a key only once the operator accepted; each subject commits
 * what the answers establish its own way (fenced field writes, or a new candidate verified before activation).
 *
 * @param connect Management access for an API key.
 * @param roles Widens an API key, used only once the operator accepted [SetupQuestion.RolesMissing].
 * @param sharedKeys Owns confirmed shared-key creation and its encrypted recovery.
 */
internal class SetupConversation(
    private val connect: (apiKey: String) -> SharedKeyApi,
    private val roles: (apiKey: String) -> CredentialRolesApi,
    private val sharedKeys: SharedKeySetup,
) {
    /** Management access for [apiKey], for a subject asking several questions with it. */
    fun management(apiKey: String): SharedKeyApi = connect(apiKey)

    /**
     * Whether [apiKey] holds every required role in [environment]. Roles it lacks and could be given are asked about,
     * with the failure that found them missing, and added only once [accepted] is that exact offer; a key that cannot
     * edit credentials, or any other failure, cannot go on.
     */
    suspend fun credential(
        apiKey: String,
        environment: TerminalEnvironment,
        accepted: KeyRolesOffer?,
    ): SetupReply<Unit> {
        val lookup = connect(apiKey).credential(environment) as? CredentialLookup.Failed ?: return SetupReply.Next(Unit)
        val failure = Failure.Remote(lookup.fault)
        val offer = KeyRoles.offer(lookup) ?: return SetupReply.Failed(failure)
        if (offer != accepted) return SetupReply.Asks(SetupQuestion.RolesMissing(offer), failure)
        return if (KeyRoles.add(roles(apiKey), environment, offer) == null) SetupReply.Next(Unit) else SetupReply.Failed(failure)
    }

    /**
     * The operator's choice [poiId] among [listed]: a terminal assigned to another account than [entered] is asked
     * about until [accepted] is its account; an ID [listed] lacks is [SetupProblem.TERMINAL_ACCESS].
     */
    fun choose(
        listed: List<TerminalDetails>,
        poiId: String,
        entered: String,
        accepted: String?,
    ): SetupReply<TerminalDetails> =
        when (val selection = TerminalAssignments.select(listed, poiId, entered)) {
            is TerminalAssignment.Assigned -> SetupReply.Next(selection.terminal)
            is TerminalAssignment.Mismatched -> accepted(selection.terminal, selection.entered, accepted)
            is TerminalAssignment.Blocked -> SetupReply.Failed(Failure.NotSetUp(selection.problem))
        }

    /**
     * The terminal [setup] is assigned in [environment]: its POIID or the one [answers] chose, else the only eligible
     * one for [entered], asking which when several are; another account than [entered] is asked about until accepted.
     */
    suspend fun discover(
        api: SharedKeyApi,
        environment: TerminalEnvironment,
        setup: TerminalSetup,
        entered: String,
        answers: SetupAnswers,
    ): SetupReply<TerminalDetails> =
        when (val assignment = TerminalAssignments.discover(api, environment, setup.poiId ?: answers.terminalId, entered)) {
            is DiscoveredAssignment.Assigned -> {
                SetupReply.Next(assignment.terminal)
            }

            is DiscoveredAssignment.Choices -> {
                SetupReply.Asks(
                    SetupQuestion.Terminals(assignment.terminals),
                    Failure.NotSetUp(SetupProblem.POI_ID),
                )
            }

            is DiscoveredAssignment.Mismatched -> {
                accepted(assignment.terminal, assignment.entered, answers.acceptedAccount)
            }

            is DiscoveredAssignment.Failed -> {
                SetupReply.Failed(assignment.failure)
            }
        }

    private fun accepted(
        terminal: TerminalDetails,
        entered: String,
        accepted: String?,
    ): SetupReply<TerminalDetails> =
        if (terminal.merchantAccount == accepted) {
            SetupReply.Next(terminal)
        } else {
            SetupReply.Asks(
                SetupQuestion.AccountDiffers(terminal.id, AccountMismatch(entered, terminal.merchantAccount)),
                Failure.NotSetUp(SetupProblem.MERCHANT_MISMATCH),
            )
        }

    /**
     * [current] connection fields with [terminal] assigned for [setup]: its account and store, its POIID unless the app
     * runs on that terminal, a network terminal's listed address when [listedAddress], and [key]'s identifier and
     * version when one was read. A blank listed account or address keeps the one entered.
     */
    fun assign(
        current: TerminalSettings,
        setup: TerminalSetup,
        terminal: TerminalDetails,
        listedAddress: Boolean,
        key: DiscoveredKey? = null,
    ): TerminalSettings =
        current.copy(
            poiIdOverride = if (setup.onTerminal) current.poiIdOverride else terminal.id,
            merchantAccount = terminal.merchantAccount.ifBlank { current.merchantAccount },
            storeId = terminal.storeId,
            host =
                if (listedAddress && setup.mode == TerminalMode.TERMINAL && !setup.onTerminal) {
                    terminal.host.ifBlank { current.host }
                } else {
                    current.host
                },
            keyIdentifier = key?.identifier ?: current.keyIdentifier,
            keyVersion = key?.version ?: current.keyVersion,
        )

    /** What Management holds as [terminal]'s shared key in [environment]; null when [setup] takes none. */
    suspend fun terminalKey(
        api: SharedKeyApi,
        environment: TerminalEnvironment,
        setup: TerminalSetup,
        terminal: TerminalDetails,
    ): SharedKeyLookup? = if (setup.needsSharedKey) api.sharedKey(terminal.id, environment) else null

    /**
     * The merchant account Tap to Pay is set up for: [entered], else the only one [api] reaches in [environment],
     * asking which when it reaches several; none is [SetupProblem.MERCHANT_ACCOUNT].
     */
    suspend fun phoneAccount(
        api: SharedKeyApi,
        environment: TerminalEnvironment,
        entered: String?,
    ): SetupReply<String> {
        if (entered != null) return SetupReply.Next(entered)
        val listed =
            when (val found = api.merchantAccounts(environment)) {
                is MerchantAccountListing.Failed -> return SetupReply.Failed(Failure.Remote(found.fault))
                is MerchantAccountListing.Listed -> found.accounts
            }
        return when {
            listed.size == 1 -> {
                SetupReply.Next(listed.single().id)
            }

            listed.isEmpty() -> {
                SetupReply.Failed(Failure.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
            }

            else -> {
                SetupReply.Asks(
                    SetupQuestion.Merchants(listed.map { SetupMerchantChoice(it.id, it.name) }),
                    Failure.NotSetUp(SetupProblem.MERCHANT_ACCOUNT),
                )
            }
        }
    }

    /**
     * The shared key set at Adyen for Tap to Pay with [merchantAccount], or [storeId] when not null: a phone has no
     * terminal to ask. A missing key is [SetupProblem.KEY_IDENTIFIER]; either failure leaves the key unread.
     */
    suspend fun phoneKey(
        api: SharedKeyApi,
        environment: TerminalEnvironment,
        merchantAccount: String,
        storeId: String?,
    ): SetupReply<DiscoveredKey> =
        when (val found = api.accountKey(merchantAccount, storeId, environment)) {
            is SharedKeyLookup.Found -> SetupReply.Next(found.key)
            SharedKeyLookup.Missing -> SetupReply.Failed(Failure.NotSetUp(SetupProblem.KEY_IDENTIFIER), keyUnread = true)
            is SharedKeyLookup.Failed -> SetupReply.Failed(Failure.Remote(found.fault), keyUnread = true)
        }

    /**
     * The shared key of [unlocked]'s terminal: the effective one, or one created once [accepted] is the offer for this
     * exact setup, asking first. [unchanged] says whether the subject still holds the setup it began on; [verified]
     * when its access was already checked.
     */
    suspend fun createKey(
        unlocked: UnlockedSetup,
        accepted: SharedKeyOffer?,
        verified: Boolean = false,
        unchanged: suspend () -> Boolean = { true },
    ): SetupReply<SharedKeySetupOutcome.Ready> =
        when (val result = sharedKeys.resolve(unlocked, accepted, verified, unchanged)) {
            is SharedKeySetupOutcome.Confirmation -> {
                SetupReply.Asks(SetupQuestion.KeyConfirmation(result.offer), Failure.NotSetUp(SetupProblem.KEY_IDENTIFIER))
            }

            is SharedKeySetupOutcome.Failed -> {
                SetupReply.Failed(result.failure)
            }

            is SharedKeySetupOutcome.Ready -> {
                SetupReply.Next(result)
            }
        }

    /** The offer of a key creation pending for [unlocked], to resume or check again; null without one. */
    suspend fun pendingKey(unlocked: UnlockedSetup): SharedKeyOffer? = sharedKeys.pendingOffer(unlocked)
}
