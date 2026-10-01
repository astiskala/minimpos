package io.minimpos.app

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.minimpos.app.ui.components.LocalAppContainer
import io.minimpos.app.ui.components.PrintJobsPreview
import io.minimpos.app.ui.navigation.AppNavHost
import io.minimpos.app.ui.theme.MiniMposTheme

/** The app's only activity: it draws edge to edge and hosts the whole Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MiniMposApplication).container
        setContent { MiniMposApp(container) }
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
