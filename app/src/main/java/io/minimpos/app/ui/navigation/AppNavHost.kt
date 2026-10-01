package io.minimpos.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.minimpos.app.feature.capture.CaptureScreen
import io.minimpos.app.feature.capture.TipScreen
import io.minimpos.app.feature.history.HistoryScreen
import io.minimpos.app.feature.history.RefundDetailScreen
import io.minimpos.app.feature.history.SaleDetailScreen
import io.minimpos.app.feature.home.HomeScreen
import io.minimpos.app.feature.lock.PinGate
import io.minimpos.app.feature.products.ProductEditScreen
import io.minimpos.app.feature.products.ProductsScreen
import io.minimpos.app.feature.refund.RefundProcessingScreen
import io.minimpos.app.feature.refund.RefundResultScreen
import io.minimpos.app.feature.refund.RefundScanScreen
import io.minimpos.app.feature.refund.RefundScreen
import io.minimpos.app.feature.sale.CheckoutScreen
import io.minimpos.app.feature.sale.PaymentScreen
import io.minimpos.app.feature.sale.SaleResultScreen
import io.minimpos.app.feature.sale.SaleScreen
import io.minimpos.app.feature.settings.SettingsScreen
import io.minimpos.app.feature.settings.SettingsSectionScreen
import io.minimpos.app.feature.transfer.TransferExportScreen
import io.minimpos.app.feature.transfer.TransferImportScreen
import io.minimpos.app.ui.components.LocalAppContainer

/**
 * Maps every [Route] to its screen, starting at [Route.Home]. Admin routes are wrapped in [PinGate], and the admin
 * area locks again once no protected route is left on the back stack. Each entry gets its own saved state and view
 * models, which are cleared when it leaves the stack.
 */
@Composable
fun AppNavHost() {
    val container = LocalAppContainer.current
    val backStack = rememberNavBackStack(Route.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val top = backStack.lastOrNull()

    // Leaving the admin area re-locks it.
    LaunchedEffect(top) {
        if (backStack.none { (it as? Route)?.isProtected == true }) container.sessionLock.lock()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<Route.Home> { HomeScreen(navigator) }
                entry<Route.Sale> { SaleScreen(navigator) }
                entry<Route.PreAuth> { SaleScreen(navigator, preAuthorisation = true) }
                entry<Route.Checkout> { CheckoutScreen(navigator, it.preAuthorisation) }
                entry<Route.Payment> { PaymentScreen(navigator, it.preAuthorisation) }
                entry<Route.SaleResult> { SaleResultScreen(it.saleId, navigator) }
                entry<Route.Tip> { TipScreen(it.saleId, navigator) }
                entry<Route.Capture> { CaptureScreen(it.saleId, it.adjustOnly, navigator) }
                entry<Route.RefundScan> { RefundScanScreen(navigator) }
                entry<Route.Refund> { RefundScreen(it.payload, it.saleId, navigator) }
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
                entry<Route.SettingsSection> { PinGate(navigator) { SettingsSectionScreen(it.section, navigator) } }
            },
    )
}
