package io.minimpos.app.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.minimpos.app.R
import io.minimpos.app.data.security.PinCheck
import io.minimpos.app.data.security.PinManager
import io.minimpos.app.ui.components.KEY_BACKSPACE
import io.minimpos.app.ui.components.KeyLabel
import io.minimpos.app.ui.components.Keypad
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.MiniScaffold
import io.minimpos.app.ui.components.currentLocale
import io.minimpos.app.ui.navigation.Navigator
import io.minimpos.app.ui.theme.LocalDimens
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Shows [content] when no PIN is set or the admin session is unlocked; otherwise asks for the PIN. */
@Composable
fun PinGate(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val container = LocalAppContainer.current
    val pinSet by container.pinManager.pinConfigured.collectAsStateWithLifecycle(initialValue = null)
    val unlocked by container.sessionLock.unlocked.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    LaunchedEffect(navigator.current) { container.sessionLock.touch(settings.security.autoLockMinutes * 60_000L) }
    Box(modifier) {
        when {
            pinSet == null -> Box(Modifier.fillMaxSize())
            pinSet == false || unlocked -> content()
            else -> UnlockScreen(onUnlock = container.sessionLock::unlock, onCancel = navigator::back)
        }
    }
}

/** Asks for the admin PIN; [onUnlock] runs once it is accepted, and wrong attempts count towards a lockout. */
@Composable
fun UnlockScreen(
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val wrongPin = stringResource(R.string.pin_wrong)
    val lockedOut = stringResource(R.string.pin_locked_out)
    val locale = currentLocale()
    MiniScaffold(title = stringResource(R.string.pin_title), onBack = onCancel, modifier = modifier) { padding ->
        PinPad(
            title = stringResource(R.string.pin_enter),
            error = error,
            busy = busy,
            modifier = Modifier.padding(padding),
            onComplete = { pin ->
                busy = true
                scope.launch {
                    when (val result = container.pinManager.verify(pin)) {
                        PinCheck.Accepted -> {
                            onUnlock()
                        }

                        is PinCheck.Rejected -> {
                            error = wrongPin.format(locale, result.attemptsLeft)
                        }

                        is PinCheck.LockedOut -> {
                            error =
                                lockedOut.format(
                                    locale,
                                    DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(result.untilMillis)),
                                )
                        }
                    }
                    busy = false
                }
            },
        )
    }
}

/** Two-step "enter new PIN / confirm" flow. */
@Composable
fun SetPinScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.pin_mismatch)
    MiniScaffold(title = stringResource(R.string.pin_set_title), onBack = onCancel, modifier = modifier) { padding ->
        PinPad(
            title = if (first == null) stringResource(R.string.pin_new) else stringResource(R.string.pin_confirm),
            error = error,
            modifier = Modifier.padding(padding),
            onComplete = { pin ->
                val entered = first
                when {
                    entered == null -> {
                        first = pin
                        error = null
                    }

                    entered == pin -> {
                        onDone(pin)
                    }

                    else -> {
                        first = null
                        error = mismatch
                    }
                }
            },
        )
    }
}

/**
 * Entry of a 4 to 8 digit PIN on an on-screen keypad, shown as dots. [onComplete] gets the PIN when the operator
 * presses OK; while [busy] the keys are disabled. The entry is cleared when [title] changes, such as between
 * entering and confirming a new PIN.
 */
@Composable
fun PinPad(
    title: String,
    error: String?,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    var pin by remember(title) { mutableStateOf("") }
    val dimens = LocalDimens.current
    val compact = dimens.compact
    Column(
        modifier = modifier.fillMaxSize().padding(dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!compact) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = dimens.titleStyle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(min = 16.dp)) {
            repeat(pin.length) { Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)) }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        Text(
            error ?: stringResource(R.string.pin_hint, PinManager.MIN_LENGTH, PinManager.MAX_LENGTH),
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("pinMessage"),
        )
        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
        PinKeys(canConfirm = PinManager.isValidPin(pin), enabled = !busy) { key ->
            when (key) {
                BACKSPACE -> pin = pin.dropLast(1)
                OK -> if (PinManager.isValidPin(pin)) onComplete(pin).also { pin = "" }
                else -> if (pin.length < PinManager.MAX_LENGTH) pin += key
            }
        }
        Spacer(Modifier.fillMaxWidth().height(8.dp))
    }
}

private const val BACKSPACE = KEY_BACKSPACE
private const val OK = "OK"

/** The 3×4 PIN keypad: digits, backspace and OK, which is only enabled when [canConfirm]; the same keys as for amounts. */
@Composable
private fun PinKeys(
    canConfirm: Boolean,
    enabled: Boolean,
    onKey: (String) -> Unit,
) = Keypad(
    keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", BACKSPACE, "0", OK),
    onKey = onKey,
    tagPrefix = "pin",
    modifier = Modifier.widthIn(max = 360.dp),
    enabled = { key -> enabled && (key != OK || canConfirm) },
    keyContent = { key ->
        if (key == OK) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok)) else KeyLabel(key)
    },
)
