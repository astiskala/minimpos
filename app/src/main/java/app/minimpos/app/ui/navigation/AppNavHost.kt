package app.minimpos.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.feature.capture.CaptureScreen
import app.minimpos.app.feature.capture.TipScreen
import app.minimpos.app.feature.history.HistoryScreen
import app.minimpos.app.feature.history.ReconciliationScreen
import app.minimpos.app.feature.history.RefundDetailScreen
import app.minimpos.app.feature.history.SaleDetailScreen
import app.minimpos.app.feature.home.HomeScreen
import app.minimpos.app.feature.lock.PinGate
import app.minimpos.app.feature.onboarding.OnboardingScreen
import app.minimpos.app.feature.products.ProductEditScreen
import app.minimpos.app.feature.products.ProductsScreen
import app.minimpos.app.feature.refund.RefundProcessingScreen
import app.minimpos.app.feature.refund.RefundResultScreen
import app.minimpos.app.feature.refund.RefundScanScreen
import app.minimpos.app.feature.refund.RefundScreen
import app.minimpos.app.feature.sale.CheckoutScreen
import app.minimpos.app.feature.sale.PaymentLinkScreen
import app.minimpos.app.feature.sale.PaymentScreen
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.feature.sale.SaleScreen
import app.minimpos.app.feature.sale.SavedCardReviewScreen
import app.minimpos.app.feature.sale.SavedCardScreen
import app.minimpos.app.feature.sale.WalletScreen
import app.minimpos.app.feature.settings.SettingsScreen
import app.minimpos.app.feature.settings.SettingsSectionScreen
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.transfer.TransferExportScreen
import app.minimpos.app.feature.transfer.TransferImportScreen
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SetupGateHost
import app.minimpos.app.ui.theme.LocalDimens

/**
 * Maps every [Route] to its screen, starting at [Route.Home] or first-run [Route.Onboarding] above Home.
 * Onboarding stays below setup screens so Back can change the initial choice. Admin routes are wrapped in [PinGate], and the admin
 * area locks again once no protected route is left on the back stack. Each entry gets its own saved state and view
 * models, which are cleared when it leaves the stack. Every screen is inside the [SetupGateHost], whose "Finish setting
 * up payments" opens Settings › Terminal.
 */
@Composable
fun AppNavHost() {
    val container = LocalAppContainer.current
    val completed = container.onboardingState.collectAsStateWithLifecycle()
    if (completed.value == null) return
    val startWithOnboarding = rememberSaveable { completed.value == false }
    val backStack =
        if (startWithOnboarding) {
            rememberNavBackStack(Route.Home, Route.Onboarding)
        } else {
            rememberNavBackStack(Route.Home)
        }
    val navigator =
        remember(backStack) {
            if (completed.value == true) backStack.removeAll { it == Route.Onboarding }
            Navigator(backStack)
        }
    val top = backStack.lastOrNull()

    // Leaving the admin area re-locks it.
    LaunchedEffect(top) {
        if (backStack.none { (it as? Route)?.isProtected == true }) container.sessionLock.lock()
        if (backStack.none { (it as? Route)?.requiresManager == true }) container.managerLock.lock()
    }

    SetupGateHost(onSetUp = { navigator.push(Route.SettingsSection(SettingsSections.TERMINAL)) }) {
        AppEntries(backStack, navigator)
    }
}

/**
 * The screens of every [Route] on [backStack]. In an expanded window (840 dp wide or more) History and Settings stay
 * beside what they open, as a list and its detail; narrower windows show one screen at a time.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AppEntries(
    backStack: NavBackStack<NavKey>,
    navigator: Navigator,
) {
    val historyList = remember { ListDetailSceneStrategy.listPane(HISTORY_PANES) { PanePlaceholder(R.string.history_choose) } }
    val historyDetail = remember { ListDetailSceneStrategy.detailPane(HISTORY_PANES) }
    val settingsList = remember { ListDetailSceneStrategy.listPane(SETTINGS_PANES) { PanePlaceholder(R.string.settings_choose) } }
    val settingsDetail = remember { ListDetailSceneStrategy.detailPane(SETTINGS_PANES) }
    NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        sceneStrategies = listOf(rememberListDetailSceneStrategy()),
        entryProvider =
            entryProvider {
                entry<Route.Home> { HomeScreen(navigator) }
                entry<Route.Onboarding> { OnboardingScreen(navigator) }
                entry<Route.Sale> { SaleScreen(navigator, SaleKind.SALE) }
                entry<Route.PreAuth> { SaleScreen(navigator, SaleKind.PRE_AUTHORISATION) }
                entry<Route.Checkout> { CheckoutScreen(navigator, it.kind) }
                entry<Route.WalletScan> { WalletScreen(navigator) }
                entry<Route.Payment> { PaymentScreen(navigator, it.kind) }
                entry<Route.SaleResult> { SaleResultScreen(it.saleId, navigator) }
                entry<Route.PaymentLink> { PaymentLinkScreen(it.saleId, it.fresh, navigator) }
                entry<Route.Tip> { PinGate(navigator, manager = true) { TipScreen(it.saleId, navigator) } }
                entry<Route.Capture> { PinGate(navigator, manager = true) { CaptureScreen(it.saleId, it.adjustOnly, navigator) } }
                entry<Route.RefundScan> { PinGate(navigator, manager = true) { RefundScanScreen(navigator) } }
                entry<Route.Refund> { PinGate(navigator, manager = true) { RefundScreen(it.payload, it.saleId, navigator) } }
                entry<Route.RefundProcessing> { RefundProcessingScreen(navigator) }
                entry<Route.RefundResult> { RefundResultScreen(it.refundId, navigator) }
                entry<Route.SavedCard> { PinGate(navigator, manager = true) { SavedCardScreen(navigator) } }
                entry<Route.SavedCardSale> { PinGate(navigator, manager = true) { SaleScreen(navigator, SaleKind.SALE, card = it.card) } }
                entry<Route.SavedCardReview> { PinGate(navigator, manager = true) { SavedCardReviewScreen(it.card, navigator) } }
                entry<Route.Reconciliation> { ReconciliationScreen(navigator) }
                entry<Route.History>(metadata = historyList) { HistoryScreen(navigator) }
                entry<Route.SaleDetail>(metadata = historyDetail) { SaleDetailScreen(it.saleId, navigator) }
                entry<Route.RefundDetail>(metadata = historyDetail) { RefundDetailScreen(it.refundId, navigator) }
                entry<Route.Products> { PinGate(navigator) { ProductsScreen(navigator) } }
                entry<Route.ProductEdit> { PinGate(navigator) { ProductEditScreen(it.productId, it.sku, navigator) } }
                entry<Route.TransferExport> { PinGate(navigator) { TransferExportScreen(navigator) } }
                entry<Route.TransferImport> { PinGate(navigator) { TransferImportScreen(navigator) } }
                entry<Route.Settings>(metadata = settingsList) { PinGate(navigator) { SettingsScreen(navigator) } }
                entry<Route.SettingsSection>(metadata = settingsDetail) {
                    PinGate(navigator) { SettingsSectionScreen(it.section, navigator) }
                }
            },
    )
}

/** What the detail pane says before anything is chosen from the list beside it ([message]). */
@Composable
private fun PanePlaceholder(
    @StringRes message: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(LocalDimens.current.screenPadding * 2),
        )
    }
}

/** The scene of History beside the sale or refund it opened. */
private const val HISTORY_PANES = "history"

/** The scene of Settings beside the section it opened. */
private const val SETTINGS_PANES = "settings"
