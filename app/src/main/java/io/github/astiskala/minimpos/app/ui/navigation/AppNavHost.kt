package io.github.astiskala.minimpos.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.feature.capture.CaptureScreen
import io.github.astiskala.minimpos.app.feature.capture.TipScreen
import io.github.astiskala.minimpos.app.feature.history.HistoryScreen
import io.github.astiskala.minimpos.app.feature.history.RefundDetailScreen
import io.github.astiskala.minimpos.app.feature.history.SaleDetailScreen
import io.github.astiskala.minimpos.app.feature.home.HomeScreen
import io.github.astiskala.minimpos.app.feature.lock.PinGate
import io.github.astiskala.minimpos.app.feature.products.ProductEditScreen
import io.github.astiskala.minimpos.app.feature.products.ProductsScreen
import io.github.astiskala.minimpos.app.feature.refund.RefundProcessingScreen
import io.github.astiskala.minimpos.app.feature.refund.RefundResultScreen
import io.github.astiskala.minimpos.app.feature.refund.RefundScanScreen
import io.github.astiskala.minimpos.app.feature.refund.RefundScreen
import io.github.astiskala.minimpos.app.feature.sale.CheckoutScreen
import io.github.astiskala.minimpos.app.feature.sale.PaymentLinkScreen
import io.github.astiskala.minimpos.app.feature.sale.PaymentScreen
import io.github.astiskala.minimpos.app.feature.sale.SaleResultScreen
import io.github.astiskala.minimpos.app.feature.sale.SaleScreen
import io.github.astiskala.minimpos.app.feature.settings.OnboardingScreen
import io.github.astiskala.minimpos.app.feature.settings.SettingsScreen
import io.github.astiskala.minimpos.app.feature.settings.SettingsSectionScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferExportScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportScreen
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer

/**
 * Maps every [Route] to its screen, starting at [Route.Home] or first-run [Route.Onboarding] above Home.
 * Onboarding stays below setup screens so Back can change the initial choice. Admin routes are wrapped in [PinGate], and the admin
 * area locks again once no protected route is left on the back stack. Each entry gets its own saved state and view
 * models, which are cleared when it leaves the stack.
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
        if (backStack.none { (it as? Route)?.requiresManager == true }) {
            container.managerLock.lock()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<Route.Home> { HomeScreen(navigator) }
                entry<Route.Onboarding> { OnboardingScreen(navigator) }
                entry<Route.Sale> { SaleScreen(navigator, SaleKind.SALE) }
                entry<Route.PreAuth> { SaleScreen(navigator, SaleKind.PRE_AUTHORISATION) }
                entry<Route.Checkout> { CheckoutScreen(navigator, it.kind) }
                entry<Route.Payment> { PaymentScreen(navigator, it.kind) }
                entry<Route.SaleResult> { SaleResultScreen(it.saleId, navigator) }
                entry<Route.PaymentLink> { PaymentLinkScreen(it.saleId, it.fresh, navigator) }
                entry<Route.Tip> { PinGate(navigator, manager = true) { TipScreen(it.saleId, navigator) } }
                entry<Route.Capture> { PinGate(navigator, manager = true) { CaptureScreen(it.saleId, it.adjustOnly, navigator) } }
                entry<Route.RefundScan> { PinGate(navigator, manager = true) { RefundScanScreen(navigator) } }
                entry<Route.Refund> { PinGate(navigator, manager = true) { RefundScreen(it.payload, it.saleId, navigator) } }
                entry<Route.RefundProcessing> { RefundProcessingScreen(navigator) }
                entry<Route.RefundResult> { RefundResultScreen(it.refundId, navigator) }
                entry<Route.History> { HistoryScreen(navigator) }
                entry<Route.SaleDetail> { SaleDetailScreen(it.saleId, navigator) }
                entry<Route.RefundDetail> { RefundDetailScreen(it.refundId, navigator) }
                entry<Route.Products> { PinGate(navigator) { ProductsScreen(navigator) } }
                entry<Route.ProductEdit> { PinGate(navigator) { ProductEditScreen(it.productId, it.sku, navigator) } }
                entry<Route.TransferExport> { PinGate(navigator) { TransferExportScreen(navigator) } }
                entry<Route.TransferImport> { PinGate(navigator) { TransferImportScreen(navigator) } }
                entry<Route.Settings> { PinGate(navigator) { SettingsScreen(navigator) } }
                entry<Route.SettingsSection> {
                    PinGate(navigator) {
                        SettingsSectionScreen(it.section, navigator, automaticSetup = it.automaticSetup)
                    }
                }
            },
    )
}
