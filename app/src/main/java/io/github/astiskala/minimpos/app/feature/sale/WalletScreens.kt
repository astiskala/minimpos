package io.github.astiskala.minimpos.app.feature.sale

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.feature.text
import io.github.astiskala.minimpos.app.payment.WalletPaymentState
import io.github.astiskala.minimpos.app.payment.WalletScanSource
import io.github.astiskala.minimpos.app.payment.WalletScanStage
import io.github.astiskala.minimpos.app.scan.ScanMode
import io.github.astiskala.minimpos.app.scan.ScannerView
import io.github.astiskala.minimpos.app.ui.components.BottomActions
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.rememberMoneyFormatter
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.payment.ScanWallet

/** Full-screen wallet selection and one-shot scanning of a prepared sale; Back preserves cart and checkout fields. */
@Composable
internal fun WalletScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: WalletViewModel = walletViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val leave = {
        vm.close()
        navigator.replace(Route.Checkout())
        Unit
    }
    BackHandler(onBack = leave)
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.background() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.stage) {
        when (state.stage) {
            WalletScanStage.PAYING -> navigator.replace(Route.Payment(SaleKind.SALE))
            WalletScanStage.CLOSED -> navigator.replace(Route.Checkout())
            WalletScanStage.CHOOSING, WalletScanStage.SCANNING, WalletScanStage.VALIDATING, WalletScanStage.PAUSED -> Unit
        }
    }
    WalletContent(state, leave, vm::choose, vm::source, vm::again, vm::accepts, vm::scanned, vm::demo, modifier)
}

@Composable
private fun walletViewModel(): WalletViewModel {
    val container = LocalAppContainer.current
    return viewModel { WalletViewModel(container.walletPayments) }
}

@Composable
private fun WalletContent(
    state: WalletPaymentState,
    onBack: () -> Unit,
    onChoose: (ScanWallet?) -> Unit,
    onSource: (WalletScanSource) -> Unit,
    onAgain: () -> Unit,
    onAccept: (String, Long) -> Boolean,
    onScan: (String, Long) -> Boolean,
    onDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val money = rememberMoneyFormatter(state.currency)
    MiniScaffold(
        title = stringResource(if (state.stage == WalletScanStage.CHOOSING) R.string.wallet_choose else R.string.checkout_scan_wallet),
        onBack = onBack,
        modifier = modifier,
        bottomBar = { WalletActions(state, onSource, onAgain, onDemo, onChoose, onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(money.format(state.amountMinor), style = dimens.amountStyle, modifier = Modifier.testTag("walletAmount"))
            if (state.stage == WalletScanStage.CHOOSING) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    state.wallets.forEach { wallet ->
                        SecondaryButton(
                            wallet.displayName,
                            { onChoose(wallet) },
                            modifier = Modifier.testTag("wallet_pick_${wallet.brand}"),
                        )
                    }
                }
            } else {
                state.selected?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium) }
                if (state.savingRequested) Text(stringResource(R.string.wallet_saving_note), style = MaterialTheme.typography.bodySmall)
                if (state.selected ==
                    ScanWallet.PAYME
                ) {
                    Text(stringResource(R.string.wallet_payme_note), style = MaterialTheme.typography.bodySmall)
                }
                state.problem?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                if (state.stage == WalletScanStage.SCANNING && state.source == WalletScanSource.CAMERA) {
                    Text(stringResource(R.string.wallet_scan_hint, state.selected?.displayName.orEmpty()), textAlign = TextAlign.Center)
                    key(state.epoch) {
                        ScannerView(
                            ScanMode.WALLET,
                            onResult = { onScan(it, state.epoch) },
                            acceptResult = { onAccept(it, state.epoch) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                } else {
                    WalletWaiting(state, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WalletWaiting(
    state: WalletPaymentState,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing),
        ) {
            if (state.stage == WalletScanStage.VALIDATING ||
                (state.source == WalletScanSource.NATIVE && state.stage == WalletScanStage.SCANNING)
            ) {
                CircularProgressIndicator()
            }
            if (state.source ==
                WalletScanSource.NATIVE
            ) {
                Text(stringResource(R.string.wallet_native_hint, state.terminalId.orEmpty()), textAlign = TextAlign.Center)
            }
            if (state.source == WalletScanSource.DEMO) Text(stringResource(R.string.wallet_demo_note), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WalletActions(
    state: WalletPaymentState,
    onSource: (WalletScanSource) -> Unit,
    onAgain: () -> Unit,
    onDemo: () -> Unit,
    onChoose: (ScanWallet?) -> Unit,
    onBack: () -> Unit,
) {
    BottomActions {
        if (state.stage ==
            WalletScanStage.PAUSED
        ) {
            PrimaryButton(stringResource(R.string.wallet_again), onAgain, modifier = Modifier.testTag("walletAgain"))
        }
        if (state.stage == WalletScanStage.SCANNING && state.source == WalletScanSource.DEMO) {
            PrimaryButton(stringResource(R.string.wallet_demo), onDemo, modifier = Modifier.testTag("walletDemo"))
        }
        if (state.stage in setOf(WalletScanStage.SCANNING, WalletScanStage.PAUSED)) {
            if (state.source !=
                WalletScanSource.CAMERA
            ) {
                SecondaryButton(stringResource(R.string.wallet_camera), { onSource(WalletScanSource.CAMERA) })
            }
            if (state.nativeScanner &&
                state.source != WalletScanSource.NATIVE
            ) {
                SecondaryButton(stringResource(R.string.wallet_native), { onSource(WalletScanSource.NATIVE) })
            }
            if (state.simulated &&
                state.source != WalletScanSource.DEMO
            ) {
                SecondaryButton(stringResource(R.string.wallet_demo_source), { onSource(WalletScanSource.DEMO) })
            }
            if (state.wallets.size > 1) SecondaryButton(stringResource(R.string.wallet_change), { onChoose(null) })
        }
        SecondaryButton(stringResource(R.string.action_cancel), onBack)
    }
}
