package io.github.astiskala.minimpos.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.astiskala.minimpos.app.terminal.PaymentsAppBridge
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.PrintJobsPreview
import io.github.astiskala.minimpos.app.ui.navigation.AppNavHost
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * The app's only activity: it draws edge to edge and hosts the whole Compose UI. It is also the Android side of the
 * Adyen Payments app's App Links ([PaymentsAppBridge]): it opens their links, and the Payments app's answers come back
 * to it (`launchMode="singleTask"`, so to this one instance).
 */
class MainActivity : ComponentActivity() {
    private val paymentsApp: PaymentsAppBridge get() = (application as MiniMposApplication).container.paymentsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MiniMposApplication).container
        // An answer that started the app (after it was stopped while the Payments app was in front) arrives here.
        if (savedInstanceState == null) intent?.dataString?.let(paymentsApp::deliver)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { paymentsApp.launches.filterNotNull().collect(::open) }
        }
        setContent { MiniMposApp(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(paymentsApp::deliver)
    }

    override fun onResume() {
        super.onResume()
        // Back from Google Play, say, where the Payments app may just have been installed.
        val container = (application as MiniMposApplication).container
        val timeout = container.settingsState.value.security.autoLockMinutes.minutes.inWholeMilliseconds
        container.sessionLock.expire(timeout)
        container.managerLock.expire(timeout)
        container.terminalStatus.readDevice()
        // Back from the Payments app: an answer comes with onNewIntent before this; without one, it is not coming.
        val waiting = paymentsApp.awaitingAnswer ?: return
        lifecycleScope.launch {
            delay(ANSWER_GRACE_MILLIS)
            paymentsApp.abandon(waiting)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as MiniMposApplication).container.userActivity()
    }

    private fun open(launch: PaymentsAppBridge.Launch) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, launch.link.toUri()).setPackage(launch.packageName))
            paymentsApp.opened(launch.id)
        } catch (ignored: ActivityNotFoundException) {
            paymentsApp.failed(launch.id, getString(R.string.setup_payments_app_missing))
        }
    }

    private companion object {
        /** How long an answer may take to arrive after the activity is back in front. */
        const val ANSWER_GRACE_MILLIS = 2_000L
    }
}

/** The whole UI, with [container] provided to every screen as [LocalAppContainer]; UI tests set it directly. */
@Composable
fun MiniMposApp(container: AppContainer) {
    MiniMposTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            AppNavHost()
            VirtualPrinterSheet()
        }
    }
}

/** Shows what the simulator "printed", as the paper slip a terminal printer would produce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VirtualPrinterSheet() {
    val container = LocalAppContainer.current
    val jobs by container.virtualPrinter.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    ModalBottomSheet(onDismissRequest = container.virtualPrinter::clear, modifier = Modifier.testTag("virtualPrinter")) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.virtual_printer_title), style = MaterialTheme.typography.titleMedium)
            PrintJobsPreview(jobs)
        }
    }
}
