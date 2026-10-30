package app.minimpos.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.ui.NavDisplay
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.feature.capture.CaptureScreen
import app.minimpos.app.feature.capture.TipScreen
import app.minimpos.app.feature.giftcard.GiftCardActivityScreen
import app.minimpos.app.feature.giftcard.GiftCardsScreen
import app.minimpos.app.feature.history.HistoryScreen
import app.minimpos.app.feature.history.ReconciliationScreen
import app.minimpos.app.feature.history.RefundDetailScreen
import app.minimpos.app.feature.history.SaleDetailScreen
import app.minimpos.app.feature.home.HomeScreen
import app.minimpos.app.feature.lock.ManagerGate
import app.minimpos.app.feature.lock.PinGate
import app.minimpos.app.feature.onboarding.OnboardingScreen
import app.minimpos.app.feature.products.ProductEditScreen
import app.minimpos.app.feature.products.ProductsScreen
import app.minimpos.app.feature.refund.RefundLegsScreen
import app.minimpos.app.feature.refund.RefundProcessingScreen
import app.minimpos.app.feature.refund.RefundResultScreen
import app.minimpos.app.feature.refund.RefundScanScreen
import app.minimpos.app.feature.refund.RefundScreen
import app.minimpos.app.feature.refund.RefundStartScreen
import app.minimpos.app.feature.refund.UnreferencedRefundScreen
import app.minimpos.app.feature.sale.CashbackHandoverScreen
import app.minimpos.app.feature.sale.CashoutScreen
import app.minimpos.app.feature.sale.CheckoutScreen
import app.minimpos.app.feature.sale.PaymentLinkScreen
import app.minimpos.app.feature.sale.PaymentScreen
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.feature.sale.SaleScreen
import app.minimpos.app.feature.sale.SavedCardReviewScreen
import app.minimpos.app.feature.sale.SavedCardScreen
import app.minimpos.app.feature.sale.SplitScreen
import app.minimpos.app.feature.sale.WalletScreen
import app.minimpos.app.feature.settings.SectionOpening
import app.minimpos.app.feature.settings.SettingsScreen
import app.minimpos.app.feature.settings.SettingsSectionScreen
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.transfer.TransferExportScreen
import app.minimpos.app.feature.transfer.TransferImportScreen
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.NavigationFrame
import app.minimpos.app.ui.components.ScreenHeader
import app.minimpos.app.ui.components.SetupGateHost
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.core.access.ManagerAction

/**
 * Maps every [Route] to its screen, starting at [Route.Home] or first-run [Route.Onboarding].
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
            rememberNavBackStack(Route.Onboarding)
        } else {
            rememberNavBackStack(Route.Home)
        }
    val navigator = remember(backStack) { Navigator(backStack) }
    val top = backStack.lastOrNull()
    val settings = container.settingsState.collectAsStateWithLifecycle()

    // Leaving the admin area re-locks it.
    LaunchedEffect(top, settings.value.security) {
        if (backStack.none { (it as? Route)?.isProtected == true }) container.sessionLock.lock()
        if (backStack.none { key ->
                val route = key as? Route
                route?.requiresManager == true || route?.entryManagerAction?.let(settings.value.security::requiresManager) == true
            }
        ) {
            container.managerLock.lock()
        }
    }

    val firstRun = navigator.isOnboarding
    DisposableEffect(firstRun) {
        container.setFirstRunSetup(firstRun)
        onDispose { container.setFirstRunSetup(false) }
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
    // Use the same directive for scene layout and its shared chrome, including window posture and insets.
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val section =
        (backStack.lastOrNull() as? Route)?.paneRoot?.takeIf {
            directive.maxHorizontalPartitions > 1 && it in backStack
        }
    val listHeader = if (section == null) ScreenHeader.FULL else ScreenHeader.LIST_PANE
    val detailHeader = if (section == null) ScreenHeader.FULL else ScreenHeader.DETAIL_PANE
    val frames =
        remember(navigator, directive.maxHorizontalPartitions) {
            paneFrames(navigator, directive.maxHorizontalPartitions > 1)
        }
    // Navigation 3 owns forward, pop and gesture-progress transitions (including system motion scale).
    // Do not wrap the display in a second animation or intercept Back at either root destination.
    NavDisplay(
        backStack = backStack,
        onBack = { navigator.back() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        sceneStrategies = listOf(rememberListDetailSceneStrategy(directive = directive)),
        sceneDecoratorStrategies = listOf(frames),
        entryProvider =
            entryProvider {
                entry<Route.Home> { HomeScreen(navigator) }
                entry<Route.Onboarding> { OnboardingScreen(navigator) }
                saleEntries(navigator)
                moneyEntries(navigator)
                entry<Route.Reconciliation> {
                    ManagerGate(
                        setOf(ManagerAction.TOTALS),
                        navigator,
                    ) { ReconciliationScreen(navigator) }
                }
                historyEntries(navigator, listHeader, detailHeader)
                entry<Route.Products> { PinGate(navigator) { ProductsScreen(navigator) } }
                entry<Route.ProductEdit> { PinGate(navigator) { ProductEditScreen(it.productId, it.sku, navigator) } }
                entry<Route.TransferExport> { PinGate(navigator) { TransferExportScreen(navigator) } }
                entry<Route.TransferImport> { PinGate(navigator) { TransferImportScreen(navigator) } }
                settingsEntries(navigator, listHeader, detailHeader)
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

/** Optional workflow-entry protection, alongside the original routes that retain a Manager approval session. */
private val Route.entryManagerAction: ManagerAction?
    get() =
        when {
            this == Route.Sale -> ManagerAction.SALE
            this == Route.PreAuth -> ManagerAction.PRE_AUTHORISATION
            this == Route.History -> ManagerAction.HISTORY
            this is Route.SaleDetail || this is Route.RefundDetail || this is Route.GiftCardDetail -> ManagerAction.HISTORY
            this == Route.Reconciliation -> ManagerAction.TOTALS
            else -> null
        }

/** Sale workflow entries, including their policy-specific approval gates. */
private fun EntryProviderScope<NavKey>.saleEntries(navigator: Navigator) {
    entry<Route.Sale> { ManagerGate(setOf(ManagerAction.SALE), navigator) { SaleScreen(navigator, SaleKind.SALE) } }
    entry<Route.PreAuth> {
        ManagerGate(setOf(ManagerAction.PRE_AUTHORISATION), navigator) { SaleScreen(navigator, SaleKind.PRE_AUTHORISATION) }
    }
    entry<Route.Checkout> { CheckoutScreen(navigator, it.kind) }
    entry<Route.WalletScan> { WalletScreen(navigator) }
    entry<Route.Payment> { PaymentScreen(navigator, it.kind) }
    entry<Route.SaleResult> { SaleResultScreen(it.saleId, navigator) }
    entry<Route.Split> { SplitScreen(it.saleId, navigator, cash = it.cash) }
    entry<Route.PaymentLink> { PaymentLinkScreen(it.saleId, it.fresh, navigator) }
    entry<Route.Tip> { ManagerGate(setOf(ManagerAction.CAPTURE), navigator) { TipScreen(it.saleId, navigator) } }
    entry<Route.Capture> {
        ManagerGate(setOf(if (it.adjustOnly) ManagerAction.ADJUST_AUTHORISATION else ManagerAction.CAPTURE), navigator) {
            CaptureScreen(it.saleId, it.adjustOnly, navigator)
        }
    }
}

/** Refund, token and gift-card workflow entries with distinct Manager permissions. */
private fun EntryProviderScope<NavKey>.moneyEntries(navigator: Navigator) {
    entry<Route.RefundStart> { RefundStartScreen(navigator) }
    entry<Route.RefundScan> { ManagerGate(setOf(ManagerAction.REFERENCED_REFUND), navigator) { RefundScanScreen(navigator) } }
    entry<Route.UnreferencedRefund> {
        ManagerGate(setOf(ManagerAction.UNREFERENCED_REFUND), navigator) { UnreferencedRefundScreen(navigator) }
    }
    entry<Route.Refund> { RefundScreen(it.payload, it.saleId, navigator) }
    entry<Route.RefundProcessing> { RefundProcessingScreen(navigator) }
    entry<Route.RefundLegs> { RefundLegsScreen(navigator) }
    entry<Route.RefundResult> { RefundResultScreen(it.refundId, navigator) }
    entry<Route.SavedCard> {
        ManagerGate(setOf(ManagerAction.SAVED_CARD_LOOKUP), navigator) { SavedCardScreen(navigator) }
    }
    entry<Route.SavedCardSale> {
        ManagerGate(setOf(ManagerAction.SALE, ManagerAction.SAVED_CARD_CHARGE), navigator) {
            SaleScreen(navigator, SaleKind.SALE, card = it.card)
        }
    }
    entry<Route.SavedCardReview> {
        ManagerGate(
            setOf(ManagerAction.SALE, ManagerAction.SAVED_CARD_CHARGE),
            navigator,
        ) { SavedCardReviewScreen(it.card, navigator) }
    }
    entry<Route.CashbackHandover> {
        CashbackHandoverScreen(it.saleId, navigator)
    }
    entry<Route.Cashout> {
        CashoutScreen(navigator)
    }
    entry<Route.GiftCards> { GiftCardsScreen(navigator) }
    entry<Route.GiftCardActivity> { GiftCardActivityScreen(it.groupId, navigator) }
}

/** Only list/detail destinations share a frame, and only while their parent list remains on the stack. */
private val Route.paneRoot: Route?
    get() =
        when {
            this == Route.Settings || this is Route.SettingsSection -> Route.Settings
            this == Route.History || this is Route.SaleDetail || this is Route.RefundDetail || this is Route.GiftCardDetail -> Route.History
            else -> null
        }

/** Entries retain their existing access gates while sharing only their presentation frame. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun EntryProviderScope<NavKey>.historyEntries(
    navigator: Navigator,
    listHeader: ScreenHeader,
    detailHeader: ScreenHeader,
) {
    val historyList =
        ListDetailSceneStrategy.listPane(HISTORY_PANES) { PanePlaceholder(R.string.history_choose) } +
            mapOf(PANE_ROOT_METADATA to Route.History)
    val historyDetail = ListDetailSceneStrategy.detailPane(HISTORY_PANES)
    entry<Route.History>(
        metadata = historyList,
    ) {
        ManagerGate(
            setOf(ManagerAction.HISTORY),
            navigator,
            header = listHeader,
        ) { HistoryScreen(navigator, header = listHeader) }
    }
    entry<Route.SaleDetail>(metadata = historyDetail) {
        ManagerGate(setOf(ManagerAction.HISTORY), navigator, header = detailHeader) {
            SaleDetailScreen(it.saleId, navigator, header = detailHeader)
        }
    }
    entry<Route.RefundDetail>(metadata = historyDetail) {
        ManagerGate(setOf(ManagerAction.HISTORY), navigator, header = detailHeader) {
            RefundDetailScreen(it.refundId, navigator, header = detailHeader)
        }
    }
    entry<Route.GiftCardDetail>(metadata = historyDetail) {
        ManagerGate(
            setOf(ManagerAction.HISTORY),
            navigator,
            header = detailHeader,
        ) { GiftCardActivityScreen(it.groupId, navigator, fromHistory = true, header = detailHeader) }
    }
}

/** Entries retain their existing access gates while sharing only their presentation frame. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun EntryProviderScope<NavKey>.settingsEntries(
    navigator: Navigator,
    listHeader: ScreenHeader,
    detailHeader: ScreenHeader,
) {
    val settingsList =
        ListDetailSceneStrategy.listPane(SETTINGS_PANES) { PanePlaceholder(R.string.settings_choose) } +
            mapOf(PANE_ROOT_METADATA to Route.Settings)
    val settingsDetail = ListDetailSceneStrategy.detailPane(SETTINGS_PANES)
    entry<Route.Settings>(metadata = settingsList) {
        PinGate(navigator, header = listHeader) { SettingsScreen(navigator, header = listHeader) }
    }
    entry<Route.SettingsSection>(metadata = settingsDetail) {
        PinGate(navigator, header = detailHeader) {
            SettingsSectionScreen(
                it.section,
                navigator,
                opening = SectionOpening(it.troubleshooting, it.chooseDestination),
                header = detailHeader,
            )
        }
    }
}

/** Chrome belongs to each scene so built-in forward/pop/predictive transitions move its header with its panes. */
private fun paneFrames(
    navigator: Navigator,
    expanded: Boolean,
): SceneDecoratorStrategy<NavKey> {
    // Value equality preserves the delegated scene's update semantics when Navigation 3 recalculates it.
    data class FramedScene(
        val original: Scene<NavKey>,
        val root: Route,
        val navigator: Navigator,
    ) : Scene<NavKey> by original {
        override val content: @Composable () -> Unit = {
            NavigationFrame(
                stringResource(if (root == Route.Settings) R.string.settings_title else R.string.history_title),
                onBack = { this.navigator.leave(root) },
            ) { original.content() }
        }
    }
    return SceneDecoratorStrategy { scene ->
        if (!expanded) return@SceneDecoratorStrategy scene
        val root = scene.entries.firstNotNullOfOrNull { it.metadata[PANE_ROOT_METADATA] as? Route } ?: return@SceneDecoratorStrategy scene
        FramedScene(scene, root, navigator)
    }
}

/** Transient entry metadata identifies a list's shared chrome without exposing Navigation 3's private entry keys. */
private const val PANE_ROOT_METADATA = "minimpos.paneRoot"
