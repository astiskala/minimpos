# `:app`

The Android app: Compose (Material 3, Navigation 3), Room, DataStore (JSON), Keystore-encrypted secrets, CameraX + ZXing
(no Google Play services on terminals), JavaMail, manual DI in `AppContainer`. The root `AGENTS.md` has the build, the
terminal constraints, where payments go and the conventions; `ArchitectureTest` enforces the starred rules.

## Architecture

- \* Layers: UI (`feature`, `ui`, `scan`, `qr`, `share`) → `payment` → `email` → `receipt` → `refund`/`terminal` →
  `data`. Only the UI reaches into `AppContainer`. View models live in `feature` and hold no Android UI types or display
  text.
- \* Only the `terminal` package talks to the terminal (and reaches the cloud and the Payments app): elsewhere (but the
  container) `:adyen`'s `transport`, `simulator` and `paymentsapp` are only the stored values
  `TerminalEnvironment`, `CloudRegion` and `SimulatedOutcome`, and `TerminalClient` only its companion helpers.
  `com.adyen` stays in `:adyen`; Room stays in `data`; crypto stays in `data.security`; nothing logs or prints.
- \* Stored sales change only through `SaleRepository.record(id, SaleEvent)` and `applyRefund`, refunds only through
  `RefundRepository.settle`; `HistoryRepository` does whole-table housekeeping (interrupted sales through
  `SaleEvent.Interrupted`). Callers name what happened (`CaptureSending`, `CaptureAnswered`, `LinkAnswered`, …);
  only `data/repo/SaleEvent.kt` decides the statuses and fields it writes (nothing else copies a stored
  `SaleEntity`). Tests write a stored state with `container.database.saleDao()`.
- \* Where a stored sale stands is `refund/PaymentStanding` (`sale.standing`); only it (and `SaleEvent`, which writes
  them) reads `captureStatus` and `holdCancelled`. Which capture statuses count as captured is
  `CaptureStatus.captured`. What can be done with a payment is `StoredPayment.actions`
  (`container.storedPayments.observe(saleId)`); `Captures` checks against the same before sending.
- \* Pure decision rules (plain JUnit tests, no Android, coroutines, repositories or clocks): `payment/Checkout`,
  `payment/PaymentLinkRequests`, `data/repo/SaleEvent`, `refund/PaymentStanding`, `refund/RefundablePayment`,
  `feature/history/HistorySearch`, `terminal/TerminalSetup`, `terminal/DestinationRules` (with the adapters'
  companions that implement it). Do not re-derive refundability, standing, checkout rules or terminal readiness
  elsewhere; what a sale's receipt says about it (tip lines, held, captured, unpaid link, paid online) is
  `ReceiptStanding`, next to `PaymentStanding`.
- \* `TerminalSetup.resolve` is the one reading of where payments go (destination, POIID, host, typed `SetupProblem`,
  `apiSetup`, printer availability, `checksConnection`), called only by `TerminalSetupSource`, which the container
  hands to `TerminalGateway`, `AdyenApi`, `TapToPaySetup` and `TerminalStatus`; whether Tap to Pay can be boarded is
  `TerminalSetup.boarding`, read the same way. Missing setup is reported typed, never thrown (the gateway's
  `Attempt.NotSetUp`, `CaptureResult.NotSetUp`, `LinkUpdate.NotSetUp`, `ActionResult.NotSetUp`), and screens word a
  `SetupProblem` in `OutcomeMessages.kt`.
- \* Only `TerminalSetupSource` reads the secrets the terminal, the cloud and the Payments app need: `unlocked()`
  decrypts the destination's and the Adyen API key once per call (`TerminalSetup.unlock`, where a saved one that
  no longer decrypts becomes the `UNREADABLE_*` problem) and hands them over as an `UnlockedSetup`; `boarding()` does
  the same for the Payments app API key (`BoardingSetup.Ready`). Nothing else in `terminal` touches `SecretStore`.
- \* Everything about one destination is in `terminal/Destinations.kt`: what it needs and can do is its
  `DestinationRules`, on its adapter's companion (`SimulatedTerminal`, `LocalTerminal`, `CloudTerminal`,
  `PaymentsAppDestination`; POIID, host, environment, setup problem, printer, secrets, abort, diagnosis, recovery
  policy, payment timeout; pure, tested through `TerminalSetup`), and the adapter only opens its transport (reused
  with `Reused`). `DestinationRules.of` picks one from the mode; `TerminalSetup` asks it. `Destination.connect` makes
  the `TerminalClient` as a `Connection` (`Open`, or `Blocked` as `NotSetUp`/`Unreachable`), the one reading of
  whether requests can be sent (nothing else constructs a `TerminalClient`). The gateway makes the adapters (the
  container the `SimulatedTerminal`), picks the one whose rules the setup holds, and is the only one that opens them.
  Outside `Destinations.kt`, `DestinationRules.kt` and the settings (`data.settings`, `feature.settings`), nothing
  names `CLOUD` or `PAYMENTS_APP`.
- \* `ApiSetup` is the one reading of the Checkout API (problem); `AdyenApi.target()` pairs it with the
  client as an `ApiTarget`. `Captures` take a `suspend () -> ApiTarget`, not `AdyenApi`; `AdyenApi` takes the
  `SimulatedTerminal`'s modifications from the container, not the gateway.
- \* Only `ApiTarget` decides adapter availability and stored payment-context eligibility (`ApiAccess`); its raw adapters
  are private. Capture and link modules keep their own sale events: unavailable setup may fail a pending creation, but
  an unknown creation stays unknown, and a context mismatch changes nothing. Only the target constructs ready access;
  only `AdyenApi` constructs or copies targets, so callers cannot replace their context or adapters.
- \* `TransactionLifecycle` (payments and refunds: PENDING first, one at a time, recheck, abort) stores only through a
  `TransactionBook` (`SaleBook`, `RefundBook`).
- \* Payment links reach Adyen only through `payment/PaymentLinks` (PENDING first, checks and cancellations one at a
  time so a late answer never overwrites a newer one), which asks `ApiTarget.links` for eligible access; only it hands
  Adyen's `PaymentLink` to the stored sale (`SaleEvent.LinkAnswered`). Whether links are offered is
  `TerminalSetup.paymentLinks`.
- \* Only `share` hands files to other apps (`FileProvider`, `ACTION_SEND`): `ShareSheet` writes the one receipt image
  to the cache folder `res/xml/shared_files.xml` names. Screens ask `TransactionActions.share()` and pass
  `TransactionActionsState.share` to `ShareEffect`; sharing is offered only while `TerminalState.canShare`.
- \* Screens get a transaction's receipt only through `feature/TransactionActions` (offer, print, email, recheck,
  automatic delivery for a fresh transaction), backed by `ReceiptDelivery`, whose operations take the
  `StoredTransaction` (sale or refund) and decide what differs between them themselves; only `ReceiptDelivery` uses
  `ReceiptFactory`, and only the container `arm`s automatic delivery. UI states other than Settings hold no
  `AppSettings` or `printerAvailable`.
- \* Outcomes are typed (`ActionOutcome`) and worded only in `feature/OutcomeMessages.kt` (in the UI only it names a
  `SetupProblem` or a `StoredReason`); tests assert outcomes, not strings. Why a stored transaction, capture or
  adjustment failed is stored as Adyen's or the terminal's words (`message`) or, when the app says so, a typed
  `StoredReason` (not set up, outcome unknown, interrupted); screens show both only through `outcomeNote()` /
  `modificationNote()`, so it reads in the current language and nothing else hands the app's words to storage. A
  tip, capture or adjustment becomes an `ActionState` only through `CaptureResult.toState(CaptureStep, …)`: nothing
  else makes its failure outcomes, and no other UI class reads `CaptureResult`'s cases.
- \* Terminal receipt fields become core receipt lines only in `ReceiptLinesJson`; print jobs are built only by
  `PrintRenderer` (a 1:1 map of `ReceiptDocument.segments()`).
- \* One `SaleSession` per `SaleKind` (`container.session(kind)`; only the container makes them). Checkout observes one
  atomic cart/form/revision snapshot; callers start through the session, never stamp or copy `PaymentStart` themselves.
  Only the session reads a start's revision; only the container completes it, through shared `completeSale` wiring for
  terminal approval and link creation. A stale checkout cannot start, and an unrelated or late start clears no newer work.
- \* Only `payment/PricingChanges` reads the pricing journal (apart from its stored settings model), constructs it,
  applies absolute catalogue prices and reprices sessions. Settings owns confirmation presentation only; startup uses
  the same recovery implementation. Checkout readiness follows this module's journal reading, not a second decision.
  Operations are serialized, a stale catalogue preview cannot commit, and sessions are repriced before the journal
  clears; replay after a failed settings write does not reprice an in-memory session twice.
- \* Settings ranges live on each settings section's companion; `SettingsRepository` normalises on every read and write
  (only it and `AppSettings` call `normalized()`), so nothing downstream clamps again. Constructor defaults define the
  current baseline; `AppSettings.forNewInstallation` adds country-specific tax defaults, not an older-build baseline.
- \* Composables other than a screen (`…Screen`) and its `…ViewModel()` factories neither take nor get a view model:
  screens pass state and callbacks (Settings bundles its sections' callbacks per view model in `SettingsEvents` and
  `TerminalSetupEvents`).
- Writes a screen starts go through `launchWrite`/`persisting` (`feature/ViewModelWrites.kt`), never a bare
  `viewModelScope.launch`, so leaving the screen cannot drop them. Never call suspending side effects inside
  `MutableStateFlow.update {}`.
- \* Only PIN entry (`feature.lock`) and `data.security` verify PINs (`PinArchitectureTest`): other screens ask for
  approval instead of reading a verifier. An optional Manager PIN protects refunds, cancellations, captures and
  adjustments independently of admin access; financial operations re-check authorization before sending.
- Secrets (shared-key passphrase, the Adyen API key, the Payments app API key, SMTP password and PIN verifiers) live
  only in `SecretStore`; never log or persist them in plain text.

## Code

- detekt does not run compiler plugins: in main code use `serializer<T>()`, not `T.serializer()`.
- Room: keep the exported current schema (`app/schemas/`) and its tests in step with the model. Earlier pre-launch
  schemas need no migration support. Never add an automatic destructive fallback or reset local data without approval.
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
