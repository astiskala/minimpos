# `:app`

Read root `AGENTS.md` for repository constraints. Domain names/owners are in `CONTEXT.md`, code contracts in KDoc.
Starred rules below are enforced by the `*ArchitectureTest` classes in `app/src/test/java/app/minimpos/app/`; run
`scripts/dev check architecture` after structural edits.

## Architecture boundaries

- \* Compose components and themes use Material 3, never Material 2. The `androidx.compose.material.icons` and
  `androidx.compose.material.ripple` packages are shared infrastructure, not Material 2 widgets. The XML window theme
  stays Android-native; it does not style Compose content.
- \* Dependencies go downwards: UI (`feature`, `ui`, `scan`, `qr`, `share`) → `payment` → `email` → `receipt` →
  `history`/`terminal` → `data`, beside the isolated `update` package. Every production class belongs to a listed
  module or the application bootstrap. Only UI reaches `AppContainer`; view models hold no UI types or display text.
- \* Only `terminal` integrates `:adyen` transports/simulator/Payments app, with the container wiring them. Elsewhere
  only stored environment/region/outcome values and `TerminalClient` companion helpers are allowed. Terminal requests
  and answers carry minor units; the payment, refund, settlement and reconciliation readings never convert units.
  `com.adyen` stays in `:adyen`, Room in `data`, crypto in `data.security`. Nothing logs or prints.
- \* Self-update is isolated in `update`: only it reads GitHub Releases, only the container arms its one startup check
  on a device that is not an Adyen terminal, and only Home offers the newer version for the browser to download
  (terminals update through the Customer Area). Checks stay silent unless they offer something.
- \* Stored mutations use `SaleRepository.record(id, SaleEvent)`/`applyRefund` and `RefundRepository.settle`.
  `HistoryRepository` owns housekeeping; only `SaleEvent` picks sale statuses/fields. Tests may seed via DAOs.
- \* Only `HistorySwitches` and the confirmed setup-import journal request full history purge; its non-secret
  destination journal blocks new financial operations until settings recovery completes. Ordinary clearing stays safe.
- \* Only `CatalogRepository` and `CataloguePricing` use the catalogue DAO outside `db`; tax-rate writes belong only
  to the repository. Deletion atomically protects referenced rates and the last rate; caller eligibility is presentation.
- \* `PaymentStanding` alone reads capture/hold status (apart from `SaleEvent` writing it); `CaptureStatus.captured`
  defines captured statuses. `StoredPayment.actions` owns allowed actions and is checked by capture operations too.
  `SaleEntity.issues` is the one issue reading; screens get whole days from `HistoryAccounting.days`.
  `saleResultNext` is the one reading of what a result screen offers next, payment links included.
- \* Decision rules stay pure: Checkout, Cashback, UnreferencedRefunds, PaymentLinkRequests, SaleEvent, TenderBalance,
  GiftCardActivity, PaymentStanding, RefundablePayment, HistorySearch, HistoryAccounting, ReceiptKind,
  TerminalAssignments, WalletOffers, TerminalSetup, TerminalSetupPlan, DestinationRules, NewInstallation, VerifiedSetup
  and settings models. No Android, coroutines, repositories or clocks; do not rederive their decisions elsewhere.
  History filters reuse accounting's issue meaning rather than reading pending/unknown status fields. `ReceiptStanding`
  owns receipt meaning; only the stored-payment reading interprets the original payment context's simulator flag for
  demo links.
- \* Work begun on one setup checks it is still stored only through `TerminalSetupSource.SetupFence`, which
  alone compares secret fingerprints. The source takes everything it calls as constructor arguments. `VerifiedSetup`
  alone decides what the verified-setup fingerprint covers; only the source reads `AppSettings.verifiedSetup`.
- \* Only `TerminalSetupSource` calls `TerminalSetup.resolve` and reads destination/API secrets. Unlock once per call;
  unreadable secrets become typed setup problems. `owner()` and `boarding()`, also for a candidate, do the same for the
  Payments app credential. Encrypted recovery journals use `EncryptedJournal` in one snapshot; only `RecoveryJournal` accesses
  their `SecretBlob` fields. Only this source applies learned environment/region facts, atomically against their
  originating terminal settings; pure destination rules decide what can be learned. Detection origins cannot be copied
  by callers.
- \* Only `terminal/SetupImport` activates QR candidates through `SetupTransfer`, using an immutable `PreparedTransfer`
  candidate; `TransferImportViewModel` reaches transfer decoding and import only through `SetupImport`. Code,
  account/access and supported connection verification precede activation writes. Its encrypted journal resumes commits
  without replaying catalog replacement or phone registration. Only it and `TerminalStatus` record verified identities
  through `TerminalSetupSource`, which automatically completes pending shared-key recovery records in `SharedKeySetup`.
- \* Only `SharedKeySetup` creates terminal-specific keys at Adyen, after explicit confirmation and encrypted recovery
  persistence. Discovery stays read-only; existing effective keys are reused, never deliberately replaced.
  Raw `SharedKeySetupOutcome` stays in `terminal`; `SetupDiscovery` owns saved-key persistence through connection
  verification, using the source's exact-key fence. Settings presents only its completed non-secret reading.
- \* Only `TapToPaySetup` provisions Payments app credentials, during explicit phone setup with encrypted creation intent
  saved first. Generated keys and pending creations stay device-local; diagnostics and payments never provision.
- \* Setup, receipt, wallet and capability tasks share `TerminalAssignments`, which alone queries `TerminalDetailsApi.terminals`
  and verifies the listing environment while preserving each task's distinct strictness.
  Only `CapabilityDiscovery` reads or updates Management capability settings (`capabilities`, `updateCapabilities`).
  Tasks receive `TerminalListingApi`, `SharedKeyApi` or `TerminalCapabilitiesApi`; only the container holds
  `TerminalDetailsApi`.
  `SetupDiscovery` returns immutable operation outcomes, not ambient problem/key-missing getters. Wallet and capability
  readings follow their setup only through `SetupReading`, which alone fences them.
- \* Destination requirements/capabilities belong to adapter companions in `Destinations.kt`; `DestinationRules.of`
  selects rules. Which Settings › Terminal steps a destination asks for (`setupSteps`) and which of its connection
  details are already supplied (`suppliedDetails`) are the rules' too; only `TerminalSetupPlan` reads them to number,
  reveal and tick the steps, and the Settings steps render the plan. Adapters open transports; only
  `Destination.connect` constructs clients, only the gateway opens adapters, all built in `TerminalGateway.adapters`.
  Outside destination/rules/settings code, don't branch on CLOUD or PAYMENTS_APP. Capabilities travel as one
  `DestinationCapabilities` value; `TerminalState` carries it whole.
- \* `ApiSetup` owns API readiness; only `AdyenApi` creates/copies `ApiTarget`. Only the target decides adapter access
  and original payment-context eligibility (`ApiAccess`); ready access and raw adapters cannot be fabricated by callers.
  `Captures` takes a target supplier, not `AdyenApi`; the simulated `CheckoutApi` is injected by the container, and
  the app never builds a Checkout part outside `AdyenCheckout`.
- \* Only `FinancialOperations` checks stored operations again, routes refunds and says whether any is running, holding
  each runner through its one `OperationKind` (busy, history release) and releasing them only itself; screens offer
  checks from `PaymentAction.CHECK_AGAIN`/`RefundEntity.canCheckAgain`, never a ServiceID. Waiting and result screens
  follow `paymentProgress`/`refundProgress` (amount as requested) through `FollowProgress` and leave results through it,
  never through a runner's own state; view-model actions with an `ActionState` run through `runAction`. Saved-card
  charges, Checkout refunds and payment-link creations are sent only by `CheckoutBook`s run by `CheckoutOperations`,
  which declines a second link per checkout so screens never guard it. `OperationIdentity.send` alone sends identified
  Checkout operations (books, captures, adjustments, refund cancellations, link expiry): replay window, approval, setup
  and original context, approval again, persistence, then what a failed send establishes. Each kind supplies an
  `IdentifiedSend` and only stores and reports what it found. Payment, history and receipt code read how a stored
  record was sent from its route (`SaleRoute`, `RefundRoute`), never from its stored link, saved-card, Checkout-refund,
  unreferenced or cash flags.
  Terminal reconciliation (recheck unresolved, read or close totals, compare) is one operation in payment
  (`TerminalReconciliation`); screens follow its outcome.
- \* Only `SavedCardLookup` lists and selects saved cards for a charge. It owns reference/target freshness and model
  eligibility; Checkout still validates pure request facts. Tokens retains its explicit lookup across
  reference/cart/review screens; checkout's automatic lookup stays screen-scoped. Only `SavedCardCharges` deletes tokens,
  serializing with charges/rechecks and invalidating every lookup before sending. Confirmed deletion follows its configured
  Manager permission; admin unlock never authorizes a protected action.
- \* Only `CashTenders` records cash tenders and gift-card cash handovers, and only `RefundLegs` records cash handed
  back; cash reaches neither Adyen nor a terminal, so the stored balance, allocation and review checks are its only
  safeguards. `TenderBalance` is the one allocation reading; returns of split sales go to every original tender through
  `RefundablePayment.requests`. `RefundBalance` is the one return-balance reading, asked by review and again inside the
  transaction storing a return.
- Only `GiftCards` starts stored-value stages, each stored before sending and recovered with its original message
  category; `GiftCardActivity` alone decides what an activity still needs (check, continue, repair, handover, undo).
  Card cashback handovers also belong to `CashTenders`; approval never implies physical cash handover.
  Gift-card value changes, repairs, handovers and balance inquiries follow their configured Manager permissions.
  Redemption follows the sale permission. Never store gift-card numbers or PINs.
- \* Unreferenced refunds start only through `StoredPaymentActions.refundWithoutReceipt`, which rechecks
  `UnreferencedRefunds` readiness (verified store country, explicit enablement, local limit); the gateway checks the
  confirmed configuration again at send and `TransactionLifecycle` the configured Manager permission. They are
  refunds in history sent as `Payment`s, so only their own book rechecks them, as payments. Only `RefundCancellations`
  asks Adyen to cancel a delayed one or records its request status; a received cancellation never means the refund stopped.
- Meal-voucher markets come only from `WalletAvailability.mealVoucherSchemes` (verified store country; currency only
  labels an offline demo). `Checkout.mealVoucher` decides what a voucher tender may do and explains blocks before
  payment; `TenderBalance.acceptsVoucher` checks the eligible amount again where the tender is stored. Voucher value is
  never returned as cash; Titres-restaurant are never refunded through the app.
- \* Only container wiring for committed history deletion releases process request/receipt registries and simulator
  identities. Retained unknown operations keep their recovery evidence; simulator period totals survive retention.
- \* `TerminalSends` alone sends, aborts and checks again stored terminal requests (`TerminalGateway` payments,
  refunds, stored value, status, abort) and releases a received late answer, only after durable resolved settlement.
  `TransactionLifecycle` stores only through `TransactionBook`, `GiftCards` through its stages. A stored Busy
  refusal's ServiceID lives on the
  sale, written and cleared only by `SaleEvent`; the book is its only reader, and the waiting screen asks
  `TransactionState.Processing.canCancel` rather than a ServiceID. Links reach Adyen/store link answers only through
  `PaymentLinks`, which serializes checks/cancellations and obtains eligible `ApiTarget` access. An unknown creation
  stays unknown when setup is unavailable or its context mismatches; a fresh blocked start records known-not-sent.
  `TerminalSetup` decides link offers.
- \* Only `share` hands files to other apps; `ShareSheet` exposes one cached image under `shared_files.xml`.
  Screens use `TransactionActions.share` and `ShareEffect`; offer only when `TerminalState.canShare`.
- \* Financial screens initiate refunds/cancellations through `StoredPaymentActions`, not pure request construction.
  It owns reference facts and busy-start handling; eligibility, foreign-receipt review and financial rechecks stay with
  their existing owners. `Captures` owns cancellation protection from committed request through stored answer;
  capture and tip screens never provide that protection themselves.
- \* Screens access receipts through `TransactionActions`, backed by `ReceiptDelivery` for `StoredTransaction`.
  Only delivery uses `ReceiptFactory`; `ReceiptKind` is the one reading both the document and its email follow. Only
  the container arms automatic delivery, and whichever screen opens first for an armed transaction claims it —
  callers pass no "first screen" flag of their own. UI state outside Settings must
  not hold `AppSettings` or `printerAvailable`. Only `TransactionActions` claims automatic delivery; only
  `ReceiptDelivery` publishes its progress and dispatches it in the application scope. Manual printing stays
  screen-scoped; automatic attempts are not retried after an uncertain send or process death.
- \* Outcomes/failures/setup problems/stored reasons are worded only in `OutcomeMessages.kt`; screens carry a
  `Failure` whole. Tests assert typed outcomes. Store app reasons as `StoredReason`, external messages verbatim; use
  `outcomeNote`/`modificationNote` at presentation. Never read exception messages (only `SmtpMailer.kt` keeps the
  mail server's reply); only `email` and stored-reason decoding create `ExternalText` in `:app`.
  Only `CaptureResult.toState(CaptureStep, …)` creates capture-action outcomes outside the capture module.
- \* Only `ReceiptLinesJson` converts Adyen receipt fields; only `PrintRenderer` builds print jobs from document segments.
- \* Only the container creates/completes one `SaleSession` per kind. Start through its atomic cart/form/revision
  snapshot, including payment, link, saved-card and scanned-wallet starts and offered-wallet checkout assembly; only the
  session reads revisions or copies `Checkout`. Stale starts cannot pay or clear newer work; terminal/link completion
  share the same wiring.
- \* Only `payment/PricingChanges` owns the pricing journal, confirmation/recovery, absolute catalog targets, session
  repricing, `withStableSettings` (wired by the container into `SampleData`) and single-pass `SettingsEdit` evaluation.
  Settings only presents confirmation. Block checkout until repricing finishes; replay must be idempotent, including
  session repricing after failed settings writes. Serialize operations and reject stale previews. Pricing intent is
  read from the stored diff in that one pass, never declared by callers: a write that reprices must be caught
  wherever it comes from.
- \* Settings ranges live on section companions; only `SettingsRepository` and `AppSettings` normalize. Do not clamp
  downstream. Constructor defaults are current; only `NewInstallation` applies country/language defaults (the one
  `StarterTax.forCountry` caller), worded through its text adapter. `TerminalSettings`
  owns destination/environment choices and their coupled fields; destination companions may copy only learned facts.
- \* App-language overrides use scoped resources, never the process-wide locale; pricing still follows the device
  country. Language stays local on setup transfer, and changing it must not rewrite saved merchant text.
- \* Below screens and their view-model factories, composables take state/callbacks, not view models. Settings bundles
  callbacks in `SettingsEvents`/`TerminalSetupEvents`; `TerminalSetupViewModel` and `ReceiptBusinessImports` observe
  narrow settings slices rather than `SettingsViewModel` or `SettingsUiState`, and `CapabilitiesViewModel` follows
  `CapabilityDiscovery.changes` from `TerminalSetupSource.discoveryChanges`.
- \* Only PIN-entry UI and `data.security` verify PINs. Financial actions request Manager approval and recheck it before
  sending; admin access is separate. Secrets remain in `SecretStore`. `payment` decides approval only through
  `ManagerApproval`: an operation keeps the grant it started with, so leaving the approving screen cannot refuse it.
  `ManagerPolicy` alone reads whether actions need or can get a Manager PIN; screens and PIN entry follow the same
  `ManagerApproval` scope (`required`, `available`) that operations check, and tests use the production policy.
  `Checkout.ceremony` decides per tender what is dropped and confirmed; `Checkout.managerActions` selects the live
  Manager permission scopes. `CardFeatures` (core) alone states which card features exclude each other, need the
  verified store, and their Manager actions; `Checkout` offers, `PaymentStart` validates, the gateway checks
  destinations (`DestinationRules.carries`) and `TerminalOperation` decides approval from it.
- \* `TerminalState.needsSetup` is read only by the container's `SetupGate`, followed by `SetupGateHost` in the nav host.
  Every UI action reaching Adyen or a terminal runs through `currentSetupGate().run`/`GatedButton(requiresSetup = true)`;
  content that only means something once set up shows `SetupRequired`. Setup tools and `StatusCheck` never do, and Home
  gates tiles by `Route.requiresSetup` rather than its own route list.
- \* Result and detail screens offer receipts only through `ReceiptActions` (one order: print, merchant copy, email,
  share, show receipt), status checks through `StatusCheck` and result bars through `ResultBar` (Home + next step).
- \* Configuration DataStores are wrapped by `StorageHealth`; never replace corrupt files with defaults. Startup work
  and protected UI wait for both settings and secrets. Read failures publish no defaults; failed writes never report success.
- \* PIN derivation stays inside `PinManager` on its injected CPU dispatcher; verification and lockout mutation are
  serialized. History, sale and product list pipelines offload transformations before sharing UI state.
- \* Receipt/email, QR and transfer preparation keep explicit CPU dispatcher boundaries. History list/maintenance
  SQL omits receipt bodies and limits batches. Sale-event writes read headers, not line/tender relations. Transfer QR
  playback suspends below STARTED; durable financial operations keep their application lifetime.
- \* Only `CameraSession` reads camera futures, after completion. Scanner disposal never waits; delayed binds and
  decoded callbacks are invalidated on close, and cleanup unbinds only the scanner's own use cases.
- \* `AppNavHost` alone owns Navigation 3 scenes and their built-in forward/pop/predictive transitions. Use AndroidX
  Back APIs, never legacy Activity Back or Activity transition overrides. Home and first-run onboarding are roots;
  setup preserves the onboarding parent until explicitly returning Home, including across recreation.

## Non-obvious implementation constraints

- Screen writes use `launchWrite`/`persisting`, except captures whose module owns their execution lifetime, so
  navigation cannot drop them.
  Never put suspending side effects in `MutableStateFlow.update`.
- detekt does not run compiler plugins: use `serializer<T>()`, not `T.serializer()`.
- Compose uses `LocalDimens` tiers and shared components. Primary actions stay in `MiniScaffold`'s `BottomActions`;
  result screens put Home beside the primary action. Split sibling content into scope extensions to preserve spacing.
- \* Only `ui.components` marks headings: upper-case `SectionHeader` groups rows or fields, sentence-case `Subheading`
  titles a block, step or card, `SheetTitle` titles a bottom sheet. Explanations use `Description` (`SettingNote` and
  `SettingIntro` in Settings), requirements and risks an INFO or WARNING `Notice`, a disabled action's precondition
  `BlockedReason`, an action's answer `ActionMessage` or a popup, and only text under an `OutcomeHeader` `OutcomeNote`.
- Theme colours keep `ThemeContrastTest` passing (WCAG AA). Content shown in place of a screen (PIN entry, Manager
  approval) handles system Back like its arrow. History and Settings open details with `Navigator.openFrom` and leave
  with `leave`, as expanded windows show them beside their detail.
- Removal of saved keys, PINs, phone registration or history uses red `ConfirmedRemoval`; Settings actions use
  `SettingActions` and icons. Secret placeholders stay one line.
- Flows: workflow screen (one pinned primary "Verb amount", confirm before money moves back) → `ProcessingContent` →
  result. Stepped screens use `StepHeader`/`StepBody`: later steps hidden until unlocked, tick once done, actions as
  main action → outcome → removals. Settings › Terminal shows setup outcomes in a dialog instead, one at a time.
- Settings fields use external titles in the same style as toggles, then primary descriptions, then inputs.
  Notes below fields hold only additional information or validation. Settings switches and choices have a short title
  plus a 1–2 sentence description. Local settings save as they change; Adyen-side capability sections edit a draft sent
  by "Save to Adyen".

## Tests

- [CONTRIBUTING's test setup](../CONTRIBUTING.md#test-setup) owns the test stack and regression suites. `SmallScreenTest`
  checks visible primary actions at AMS1 `w320dp-h460dp-hdpi`, P630 and S1F2 sizes.
- `TestEnvironment` is rule order 0, `createRecordingComposeRule` order 1 (v2 rule with pre-teardown failure artifacts;
  CONTRIBUTING owns paths and usage). FakeDevice/FakeTerminal model terminals; FakeCloud,
  FakePaymentsApp, FakeManagement and FakeLinkApi model external services (`env.useLinks()` enables link setup).
  Call `container.start()` for background connection checks. Use fake DNS; no network in tests.
  Startup tests inject `UnconfinedTestDispatcher` into `TestEnvironment` for eager startup and run the test with a
  `StandardTestDispatcher` sharing its scheduler. This keeps assertions and Robolectric teardown on the test thread
  after DataStore resumes on IO. Suspend for state changes so queued continuations and debounced checks can progress.
- `FileProvider` caches roots across Robolectric tests; `forgetSharedFileRoots` clears them. Filesystem fixtures must
  also be isolated across the two worker JVMs.
- Wait with `compose.awaitCondition`, not `await` (main-looper deadlock). After saving, wait for the editor to close.
  Transfer view-model tests run the main looper with `TransferViewModelsTest.settled`.
- Robolectric never idles with a text field in an AlertDialog/default-width Dialog. Use
  `DialogProperties(usePlatformDefaultWidth = false)` or test its content separately.
