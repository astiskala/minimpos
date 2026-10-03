# `:app`

Read root `AGENTS.md` for repository constraints. Domain names/owners are in `CONTEXT.md`, code contracts in KDoc.
Starred rules below are enforced by `ArchitectureTest` (PIN rules also by `PinArchitectureTest`).

## Architecture boundaries

- \* Dependencies go downwards: UI (`feature`, `ui`, `scan`, `qr`, `share`) → `payment` → `email` → `receipt` →
  `refund`/`terminal` → `data`. Only UI reaches `AppContainer`; view models hold no UI types or display text.
- \* Only `terminal` integrates `:adyen` transports/simulator/Payments app, with the container wiring them. Elsewhere
  only stored environment/region/outcome values and `TerminalClient` companion helpers are allowed. `com.adyen` stays
  in `:adyen`, Room in `data`, crypto in `data.security`. Nothing logs or prints.
- \* Stored mutations use `SaleRepository.record(id, SaleEvent)`/`applyRefund` and `RefundRepository.settle`.
  `HistoryRepository` owns housekeeping; only `SaleEvent` picks sale statuses/fields. Tests may seed via DAOs.
- \* `PaymentStanding` alone reads capture/hold status (apart from `SaleEvent` writing it); `CaptureStatus.captured`
  defines captured statuses. `StoredPayment.actions` owns allowed actions and is checked by capture operations too.
- \* Decision rules stay pure: Checkout, PaymentLinkRequests, SaleEvent, PaymentStanding, RefundablePayment,
  HistorySearch, TerminalSetup and DestinationRules. No Android, coroutines, repositories or clocks; do not rederive
  their decisions elsewhere. `ReceiptStanding` owns receipt meaning.
- \* Only `TerminalSetupSource` calls `TerminalSetup.resolve` and reads destination/API secrets. Unlock once per call;
  unreadable secrets become typed setup problems. `boarding()` does the same for the Payments app credential.
- \* Destination requirements/capabilities belong to adapter companions in `Destinations.kt`; `DestinationRules.of`
  selects rules. Adapters open transports; only `Destination.connect` constructs clients, only the gateway opens
  adapters. Outside destination/rules/settings code, don't branch on CLOUD or PAYMENTS_APP.
- \* `ApiSetup` owns API readiness; only `AdyenApi` creates/copies `ApiTarget`. Only the target decides adapter access
  and original payment-context eligibility (`ApiAccess`); ready access and raw adapters cannot be fabricated by callers.
  `Captures` takes a target supplier, not `AdyenApi`; simulator modifications are injected by the container.
- \* `TransactionLifecycle` stores only through `TransactionBook`. Links reach Adyen/store link answers only through
  `PaymentLinks`, which serializes checks/cancellations and obtains eligible `ApiTarget` access. An unknown creation
  stays unknown when setup is unavailable; a context mismatch must change nothing. `TerminalSetup` decides link offers.
- \* Only `share` hands files to other apps; `ShareSheet` exposes one cached image under `shared_files.xml`.
  Screens use `TransactionActions.share` and `ShareEffect`; offer only when `TerminalState.canShare`.
- \* Screens access receipts through `TransactionActions`, backed by `ReceiptDelivery` for `StoredTransaction`.
  Only delivery uses `ReceiptFactory`; only the container arms automatic delivery. UI state outside Settings must
  not hold `AppSettings` or `printerAvailable`.
- \* Outcomes/setup problems/stored reasons are worded only in `OutcomeMessages.kt`. Tests assert typed outcomes.
  Store app reasons as `StoredReason`, external messages verbatim; use `outcomeNote`/`modificationNote` at presentation.
  Only `CaptureResult.toState(CaptureStep, …)` creates capture-action outcomes outside the capture module.
- \* Only `ReceiptLinesJson` converts Adyen receipt fields; only `PrintRenderer` builds print jobs from document segments.
- \* Only the container creates/completes one `SaleSession` per kind. Start through its atomic cart/form/revision
  snapshot; only the session reads revisions. Stale starts cannot pay or clear newer work; terminal/link completion
  share the same wiring.
- \* Only `payment/PricingChanges` owns the pricing journal, confirmation/recovery, absolute catalog targets and session
  repricing. Settings only presents confirmation. Block checkout until repricing finishes; replay must be idempotent,
  including session repricing after failed settings writes. Serialize operations and reject stale previews.
- \* Settings ranges live on section companions; only `SettingsRepository` and `AppSettings` normalize. Do not clamp
  downstream. Constructor defaults are current; installation applies country/language defaults.
- \* Below screens and their view-model factories, composables take state/callbacks, not view models. Settings bundles
  callbacks in `SettingsEvents`/`TerminalSetupEvents`.
- \* Only PIN-entry UI and `data.security` verify PINs. Financial actions request Manager approval and recheck it before
  sending; admin access is separate. Secrets remain in `SecretStore`.

## Non-obvious implementation constraints

- Screen writes use `launchWrite`/`persisting`, not a bare `viewModelScope.launch`, so navigation cannot drop them.
  Never put suspending side effects in `MutableStateFlow.update`.
- detekt does not run compiler plugins: use `serializer<T>()`, not `T.serializer()`.
- Compose uses `LocalDimens` tiers and shared components. Primary actions stay in `MiniScaffold`'s `BottomActions`;
  result screens put Home beside the primary action. Split sibling content into scope extensions to preserve spacing.
- Removal of saved keys, PINs, phone registration or history uses red `ConfirmedRemoval`; Settings actions use
  `SettingActions` and icons. Secret placeholders stay one line.

## Tests

- Robolectric SDK 33, `TestApplication`, Compose v2, `en-rAU`. `SmallScreenTest` checks visible primary actions at AMS1
  `w320dp-h460dp-hdpi`, P630 and S1F2 sizes. Localization tests cover resource parity and CJK checkout.
- `TestEnvironment` is rule order 0, Compose order 1. FakeDevice/FakeTerminal model terminals; FakeCloud,
  FakePaymentsApp, FakeManagement and FakeLinkApi model external services (`env.useLinks()` enables link setup).
  Call `container.start()` for background connection checks. Use fake DNS; no network in tests.
- `FileProvider` caches roots across Robolectric tests; `forgetSharedFileRoots` clears them. Filesystem fixtures must
  also be isolated across the two worker JVMs.
- Wait with `compose.awaitCondition`, not `await` (main-looper deadlock). After saving, wait for the editor to close.
  Transfer view-model tests run the main looper with `TransferViewModelsTest.settled`.
- Robolectric never idles with a text field in an AlertDialog/default-width Dialog. Use
  `DialogProperties(usePlatformDefaultWidth = false)` or test its content separately.
