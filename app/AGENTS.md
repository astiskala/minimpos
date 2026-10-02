# `:app`

The Android app: Compose (Material 3, Navigation 3), Room, DataStore (JSON), Keystore-encrypted secrets, CameraX + ZXing
(no Google Play services on terminals), JavaMail, manual DI in `AppContainer`. The root `AGENTS.md` has the build, the
terminal constraints, where payments go and the conventions; `ArchitectureTest` enforces the starred rules.

## Architecture

- \* Layers: UI (`feature`, `ui`, `scan`, `qr`, `share`) → `payment` → `email` → `receipt` → `refund`/`terminal` →
  `data`. Only the UI reaches into `AppContainer`. View models live in `feature` and hold no Android UI types or display
  text.
- \* Only the `terminal` package talks to the terminal (and reaches the cloud and the Payments app): elsewhere (but the
  container) `:terminal-api`'s `transport`, `simulator` and `paymentsapp` are only the stored values
  `TerminalEnvironment`, `CloudRegion` and `SimulatedOutcome`, and `TerminalClient` only its companion helpers.
  `com.adyen` stays in `:terminal-api`; Room stays in `data`; crypto stays in `data.security`; nothing logs or prints.
- \* Stored sales change only through `SaleRepository`'s named transitions (`markSending`, `settle`, `recordAdjustment`,
  `recordCapture`, `modificationFailed`, `markEmailed`, `applyRefund`), refunds only through `RefundRepository.settle`;
  `HistoryRepository` does whole-table housekeeping. Tests write a stored state with `container.database.saleDao()`.
- \* Where a stored sale stands is `refund/PaymentStanding` (`sale.standing`); only it reads `captureStatus` and
  `holdCancelled`. What can be done with a payment is `StoredPayment.actions`
  (`container.storedPayments.observe(saleId)`); `Captures` checks against the same before sending.
- \* Pure decision rules (plain JUnit tests, no Android, coroutines, repositories or clocks): `payment/Checkout`,
  `payment/PaymentLinkRequests`, `refund/PaymentStanding`, `refund/RefundablePayment`, `feature/history/HistorySearch`,
  `terminal/TerminalSetup`. Do not re-derive refundability, standing, checkout rules or terminal readiness elsewhere;
  what a sale's receipt says about it (tip lines, held, captured, unpaid link, paid online) is `ReceiptStanding`, next
  to `PaymentStanding`.
- \* `TerminalSetup.resolve` is the one reading of where payments go (mode, POIID, host, typed `SetupProblem`,
  `apiSetup`, printer availability, `checksConnection`), called only by `TerminalSetupSource`, which the container
  hands to `TerminalGateway`, `AdyenApi`, `TapToPaySetup` and `TerminalStatus`; whether Tap to Pay can be boarded is
  `TerminalSetup.boarding`, read the same way. Missing setup is reported in outcomes, never thrown; screens word a
  `SetupProblem` in `OutcomeMessages.kt`, and messages stored with a transaction or capture are worded by
  `TerminalSetupSource.describe` (the container's resource lookup).
- \* What a destination can do lives in its `terminal/Destination` adapter (`SimulatedTerminal`, `LocalTerminal`,
  `CloudTerminal`, `PaymentsAppDestination`): opening its transport (reused with `Reused`), abort, diagnosis, recovery
  policy and payment timeout; `Destination.connect` makes the `TerminalClient` as a `Connection` (`Open`, or `Blocked`
  as `NotSetUp`/`Unreachable`), the one reading of whether requests can be sent (nothing else constructs a
  `TerminalClient`). The gateway makes the destinations (the container the `SimulatedTerminal`) and is the only one
  that asks them; it asks the destination, never the mode. Outside `TerminalSetup`, the gateway's choice of destination
  and the settings (`data.settings`, `feature.settings`), nothing names `CLOUD` or `PAYMENTS_APP`.
- \* `ApiSetup` is the one reading of the Checkout API (capture mode, problem); `AdyenApi.target()` pairs it with the
  client as an `ApiTarget`. `Captures` take a `suspend () -> ApiTarget`, not `AdyenApi`; `AdyenApi` takes the
  `SimulatedTerminal`'s modifications from the container, not the gateway.
- \* `TransactionLifecycle` (payments and refunds: PENDING first, one at a time, recheck, abort) stores only through a
  `TransactionBook` (`SaleBook`, `RefundBook`).
- \* Payment links reach Adyen only through `payment/PaymentLinks` (PENDING first, checks and cancellations one at a
  time so a late answer never overwrites a newer one), which takes `ApiTarget.links` like `Captures`; only it hands
  `SaleRepository.settle` Adyen's `PaymentLink`. Whether links are offered is `TerminalSetup.paymentLinks`.
- \* Only `share` hands files to other apps (`FileProvider`, `ACTION_SEND`): `ShareSheet` writes the one receipt image
  to the cache folder `res/xml/shared_files.xml` names. Screens ask `TransactionActions.share()` and pass
  `TransactionActionsState.share` to `ShareEffect`; sharing is offered only while `TerminalState.canShare`.
- \* Screens get a transaction's receipt only through `feature/TransactionActions` (offer, print, email, recheck,
  automatic delivery for a fresh transaction), backed by `ReceiptDelivery`; only `ReceiptDelivery` uses
  `ReceiptFactory`, and only the container `arm`s automatic delivery. UI states other than Settings hold no
  `AppSettings` or `printerAvailable`.
- \* Outcomes are typed (`ActionOutcome`) and worded only in `feature/OutcomeMessages.kt` (in the UI only it names a
  `SetupProblem`; `textRes` is for it and the container); tests assert outcomes, not strings. A tip, capture or
  adjustment becomes an `ActionState` only through `CaptureResult.toState(CaptureStep, …)`: nothing else makes its
  failure outcomes, and no other UI class reads `CaptureResult`'s cases.
- \* Terminal receipt fields become core receipt lines only in `ReceiptLinesJson`; print jobs are built only by
  `PrintRenderer` (a 1:1 map of `ReceiptDocument.segments()`).
- \* One `SaleSession` per `SaleKind` (`container.session(kind)`; only the container makes them); the payments'
  lifecycle clears it once approved.
- \* Settings ranges live on each settings section's companion; `SettingsRepository` normalises on every read and write
  (only it and `AppSettings` call `normalized()`), so nothing downstream clamps again. New `AppSettings` fields need
  defaults, so settings saved by older versions still load.
- \* Composables other than a screen (`…Screen`) and its `…ViewModel()` factories neither take nor get a view model:
  screens pass state and callbacks (Settings bundles its sections' callbacks per view model in `SettingsEvents` and
  `TerminalSetupEvents`).
- Writes a screen starts go through `launchWrite`/`persisting` (`feature/ViewModelWrites.kt`), never a bare
  `viewModelScope.launch`, so leaving the screen cannot drop them. Never call suspending side effects inside
  `MutableStateFlow.update {}`.
- Secrets (shared-key passphrase, the API key for Checkout and the cloud, the Payments app API key, SMTP password, PIN
  verifier) live only in `SecretStore`; never log or persist them in plain text.

## Code

- detekt does not run compiler plugins: in main code use `serializer<T>()`, not `T.serializer()`.
- Room: bump the version, commit the exported schema (`app/schemas/`), add an auto-migration or a hand-written one in
  `AppDatabase.withMigrations`, and extend `DatabaseMigrationTest`.
- Compose: detekt's `LongMethod` (60 lines) and `CyclomaticComplexMethod` apply to composables; split into private
  composables and `ColumnScope`/`RowScope` extensions, and name event lambdas in the present tense (`onSkuScan`).
- Sizes come from `LocalDimens` tiers (`Compact` < 360 dp wide or < 520 dp tall, `Medium` < 640 dp tall, else
  `Regular`); prefer `Dimens` fields over hard-coded values. Primary actions go in
  `MiniScaffold(bottomBar = { BottomActions { … } })`. Reuse `ui/components` (buttons, search, `OutcomeHeader`,
  `TransactionRow`, `Keypad`, …); result screens put `HomeButton` beside their primary action.

## Tests

- Robolectric at SDK 33 with `TestApplication`; Compose tests use the v2 rule and `en-rAU` (amounts show as `$4.50`).
  `SmallScreenTest` checks primary actions are visible without scrolling at `w320dp-h460dp-hdpi` (AMS1), plus P630 and
  S1F2 sizes.
- `LocalizationTest` checks translation/format parity and receipt defaults and writes sample previews under
  `app/build/reports/localization/`; `LocalizedUiTest` checks Chinese/Japanese checkout at AMS1 size.
- `TestEnvironment(device = FakeDevice(detectedPoiId = …), terminal = FakeTerminal())` plays a terminal; `FakeCloud`,
  `FakePaymentsApp` (simulator behind encrypted App Links), `FakeManagement` and `FakeLinkApi` play the cloud, the
  Payments app (`FakeDevice(paymentsApps = …)`), its boarding and payment links (`env.useLinks()` sets up the Checkout
  API and switches links on). `FileProvider` caches its folders statically across Robolectric tests, so the
  environment clears them (`forgetSharedFileRoots`). It is a rule declared `@get:Rule(order = 0)` before the compose rule
  (`order = 1`). Call `container.start()` for the background connection check. No network or DNS in tests (give
  `TerminalHttpClient` a fake `Dns`).
- In Compose tests wait with `compose.awaitCondition`, never `await` (it blocks the main looper and deadlocks on CI);
  after a save, wait for the editor to close. Transfer import view-model tests run the main looper while waiting
  (`TransferViewModelsTest.settled`). Busy cores (a dozen `yes > /dev/null`) reproduce CI-only timeouts.
- Under Robolectric, Compose never idles when an `AlertDialog` (or a platform-default-width `Dialog`) contains a text
  field: use a `Dialog` with `DialogProperties(usePlatformDefaultWidth = false)`, or test that content as its own
  composable (`TaxRateForm`/`TaxRateFormTest`).
