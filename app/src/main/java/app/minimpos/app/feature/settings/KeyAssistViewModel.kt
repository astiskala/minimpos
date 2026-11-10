package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.KeyRolesOffer
import app.minimpos.app.terminal.SetupDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Where the help beside Settings › Terminal's key setup stands.
 *
 * @property rolesOffer Roles the API key lacks that it could be given, awaiting the operator; null otherwise.
 * @property rolesAdded Whether the latest addition of roles was confirmed, so the key can be tested again.
 */
data class KeyAssistState(
    val rolesOffer: KeyRolesOffer? = null,
    val rolesAdded: Boolean = false,
)

/**
 * Help beside Settings › Terminal's key setup: adding the roles an API key lacks once the operator agrees, and checking
 * a pending shared key again by itself while its step is shown.
 *
 * @param discovery Reads what the saved API key lacks and adds it.
 */
class KeyAssistViewModel(
    private val discovery: SetupDiscovery,
) : ViewModel() {
    private var follow: Job? = null

    /** The roles offer and whether roles were added. */
    val state: StateFlow<KeyAssistState>
        field = MutableStateFlow(KeyAssistState())

    private var combined: Pair<TerminalSetupViewModel, StateFlow<TerminalSetupActions>>? = null

    /** [setup]'s actions with this roles offer, as Settings › Terminal shows them together. */
    fun alongside(setup: TerminalSetupViewModel): StateFlow<TerminalSetupActions> =
        combined?.takeIf { it.first === setup }?.second
            ?: combine(
                setup.actions,
                state,
            ) { actions, help -> actions.copy(keyRolesOffer = help.rolesOffer, keyRolesAdded = help.rolesAdded) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, setup.actions.value)
                .also { combined = setup to it }

    /** Looks for roles the saved API key lacks and could be given, after a test found a role missing. */
    fun offerKeyRoles() {
        launchWrite({
            val offer = discovery.keyRolesOffer()
            state.update { it.copy(rolesOffer = offer, rolesAdded = false) }
        })
    }

    /** Adds the offered roles to the API key, or leaves the key as it is. */
    fun reviewKeyRoles(accept: Boolean) {
        val offer = state.value.rolesOffer ?: return
        state.update { it.copy(rolesOffer = null) }
        if (accept) {
            launchWrite({
                val added = discovery.addKeyRoles(offer)
                state.update { it.copy(rolesAdded = added) }
            })
        }
    }

    /** While [following], calls [check] every few seconds, so the operator need not come back and tap. */
    fun followSharedKey(
        following: Boolean,
        check: () -> Unit,
    ) {
        follow?.cancel()
        follow =
            if (following) {
                viewModelScope.launch {
                    while (isActive) {
                        delay(KEY_RECHECK)
                        check()
                    }
                }
            } else {
                null
            }
    }
}

private val KEY_RECHECK = 10.seconds
