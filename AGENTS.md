# Mini mPOS

Android POS app for Adyen Android payment terminals; it takes payments through the local Terminal API
(`https://localhost:8443/nexo`) on the same device. KDoc in the code is the detailed reference; this file holds what
the code does not tell you. `CONTEXT.md` is the domain glossary: use its terms (and code names) for new modules,
tests and docs, and add a term there before naming a module after a new concept.

## Modules
- `:core` – pure Kotlin: money/tax, cart, refund apportioning, receipt document model and renderers, QR formats
  (`TransferCodec` `MPC1:`, refund `MPR1*`), Adyen currency table (Adyen's decimals win over ISO), `PaymentMethods`.
  There is deliberately no country/region setting (blank currency follows the device's country, else EUR).
- `:terminal-api` – wraps `com.adyen:adyen-java-api-library` (nexo models, `TerminalLocalAPI`, `NexoCrypto`). Packages,
  layered downwards: `simulator` → `client` (`TerminalClient`), `checkout` (Checkout API v72 posted with OkHttp + Gson,
  because the library's Checkout models need Jackson and keep rules for hundreds of classes), `paymentsapp` (Adyen
  Payments app: App Links, `PaymentsAppTransport`, Management API boarding; only the app uses it) → `transport`
  (`TerminalTls`, local and Cloud device API transports with OkHttp; the library's `CloudDeviceApi` is not used, its
  `tapi` models need Jackson) → `parse`. Every call to Adyen's HTTPS APIs (cloud, Checkout, Management) goes through
  `transport/AdyenHttp` (no silent retries, per-call timeout, failures typed as sent/not sent; ArchUnit keeps OkHttp
  requests in `transport` and calls in `AdyenHttp` and the library's `TerminalHttpClient`); transports that answer
  status requests themselves repeat responses from `CompletedTransactions`. `TerminalTransport.send` returns a
  `Delivery` (`Answered`, `NotSent`, `MaybeSent`), never throws: each transport works out once whether a request can
  have taken effect (the exceptions stay inside the library's HTTP client and App Link exchanges, turned into a
  `Delivery` with `toDelivery`; `client` and `checkout` never see them). Why a transaction was not approved
  (cancelled, busy, retry advice) is `client/Decline` (`TransactionDetails.decline`, `SaleEntity.decline`); nothing
  else compares ErrorCondition strings (enforced in both modules). Its interface uses only its own types (`PrintJob`,
  `ReceiptField`, …); it knows neither Android nor `:core`. The in-process `TerminalSimulator` mirrors Adyen's
  behaviour (pre-auth blobs, `InProgress` status, cancellations) and shares a ledger with its `SimulatedModifications`.
- `:app` – Compose (Material 3, Navigation 3), Room, DataStore (JSON), Keystore-encrypted secrets, CameraX + ZXing (no
  Google Play services on terminals), JavaMail, manual DI in `AppContainer`.

## Build and verify
- Run `./gradlew qualityGate` after every change: Spotless/ktlint, detekt (no baseline), Dokka with `failOnWarning`
  (every `[link]` in KDoc must resolve), Android Lint (warnings are errors), unit/Robolectric/Compose tests, ArchUnit
  `ArchitectureTest` in each module, `AndroidApiLevelTest` (in `:app`), Kover thresholds (core 95/85, terminal-api
  90/75, app non-UI 80 line/branch %), and `verify{Debug,Release}TerminalManifest`. Kotlin warnings are errors; the
  build output stays warning-free.
- A new architectural decision gets its ArchUnit rule in the same change (a starred bullet below), with a comment
  saying why; check a new rule fails on a deliberate violation before relying on it.
- Format with `./gradlew spotlessApply`. After editing `.editorconfig`, run `./gradlew --stop` (ktlint caches it).
- detekt does not run compiler plugins: in `:app` main code use `serializer<T>()`, not `T.serializer()`.
- Fix findings instead of silencing them: no new `@Suppress`, lint ignores, baselines or rule exclusions. The existing
  ones are deliberate and commented.
- Versions live in `version.properties`; never bump them by hand. Releases come only from the Release workflow
  (`.github/workflows/release.yml`), which raises the version, signs, tags and publishes.
- Commits use Adam Stiskala <github@adamstiskala.com> (not the Adyen work email) and carry no bot attribution.
- Release APK: `./gradlew :app:assembleRelease`, signed only when `keystore.properties` exists (never commit it; the
  key is `~/.android/minimpos-release.jks`, alias `minimpos`, outside the repo).
- Security advisories on transitive dependencies (Dependabot cannot fix them): add the patched version, at least 7
  days old, to the `patched` table in `settings.gradle.kts`; check `./gradlew <module>:dependencies buildEnvironment`
  and run the gate plus the signed release build. Drop entries once upstream catches up.
- Dead-code audit: after `:app:assembleRelease`, the first block of `app/build/outputs/mapping/release/usage.txt`
  (before any `androidx.*` entry) lists public members production never reaches. Check `@Serializable`/Room members by
  hand (kept by rules).
- Room: bump the version, commit the exported schema (`app/schemas/`), add an auto-migration or a hand-written one in
  `AppDatabase.migrations`, and extend `DatabaseMigrationTest`.

## Adyen terminal constraints
- `VerifyTerminalManifestTask` enforces: minSdk 28, no CATEGORY_HOME, no `testOnly`, only allowlisted permissions
  (INTERNET, ACCESS_NETWORK_STATE, CAMERA; `StripManifestPermissionsTask` removes androidx's
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), and a PNG-only `android:icon` (the Customer Area cannot render adaptive
  icons; never add an adaptive `ic_launcher`; `MainActivity` has its own `ic_launcher_adaptive`).
- `applicationId` is `io.github.astiskala.minimpos`; never change it or reuse the abandoned `io.minimpos.app` (the Kotlin
  package/namespace). Each package is tied to the key of its first upload: always sign with the same key, and upload a
  new `versionCode` each time. Unsigned APKs upload fine but fail to install on terminals.
- Smallest target screen: AMS1, 4" 480×800 hdpi, ~320×460 dp usable, Android 10, no printer.
- On a terminal the POIID comes from `Settings.Global.DEVICE_NAME` and the host is `localhost`; only the shared key is
  entered. The manifest's `<queries>` (the two Payments app packages) and the `minimpos://paymentsapp` VIEW filter on the
  `singleTask` `MainActivity` add no permission, so the same APK stays acceptable for terminals.
- `:core` and `:terminal-api` are JVM modules, so Android Lint does not check their API levels: `AndroidApiLevelTest`
  checks every Java/Android class, method and field they reach against the compile SDK's `api-versions.xml` at the
  app's minSdk, accepting what D8 backports (`listBackportedMethods`). E.g. `URLEncoder.encode(String, Charset)` is
  API 33; use the charset name.
- There is no adb on terminals: test on an emulator with "Payments go to" = Simulator (the default off a
  terminal), or a terminal on the LAN. Use the `docs-screenshots` skill (`.devin/skills/docs-screenshots/SKILL.md`)
  for the AMS1 emulator and the website screenshots.

## Adyen Java library on Android
- Always set our `TerminalHttpClient` on the `Client`; the default Apache client crashes on Android. Inside
  `Client(...).apply { }` a bare `httpClient` calls `Client.getHttpClient()`, which creates it (ArchUnit forbids that
  call).
- `TerminalLocalAPI` always posts to `<endpoint>:8443/nexo/`. `xerces:xercesImpl` supplies `DatatypeFactory`.
- The library ships no R8 rules: `app/proguard-rules.pro` keeps the models and serialisation classes. Check the release
  dex after upgrading it (the lint ignore for `TrustAllX509TrustManager` relies on R8 stripping the TEST-only API).
- Unknown enum values deserialise to null. Application info goes on every payment and refund, formatted identically.

## Architecture (`ArchitectureTest` in each module enforces the starred rules)
- \* `:app` layers: UI (`feature`, `ui`, `scan`, `qr`) → `payment` → `email` → `receipt` → `refund`/`terminal` →
  `data`. Only the UI reaches into `AppContainer`. View models live in `feature` and hold no Android UI types or
  display text.
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
  `refund/PaymentStanding`, `refund/RefundablePayment`, `feature/history/HistorySearch`, `terminal/TerminalSetup`.
  Do not re-derive refundability, standing, checkout rules or terminal readiness elsewhere; what a sale's receipt says
  about it (tip lines, held, captured) is `ReceiptStanding`, next to `PaymentStanding`.
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
- The terminal's own receipt printing is suppressed (`tenderOption=ReceiptHandler`): the app prints one combined slip
  (header, items, tax, Adyen's card receipt lines, footer), with the refund QR code as a second print request.
- \* One `SaleSession` per `SaleKind` (`container.session(kind)`; only the container makes them); the payments'
  lifecycle clears it once approved.
- \* Settings ranges live on each settings section's companion; `SettingsRepository` normalises on every read and write
  (only it and `AppSettings` call `normalized()`), so nothing downstream clamps again.
- \* Composables other than a screen (`…Screen`) and its `…ViewModel()` factories neither take nor get a view model:
  screens pass state and callbacks (Settings bundles its sections' callbacks per view model in `SettingsEvents` and
  `TerminalSetupEvents`).
- Writes a screen starts go through `launchWrite`/`persisting` (`feature/ViewModelWrites.kt`), never a bare
  `viewModelScope.launch`, so leaving the screen cannot drop them. Never call suspending side effects inside
  `MutableStateFlow.update {}`.
- "Payments go to" (`TerminalMode`): this terminal or one on the network (local Terminal API, shared key), `CLOUD`
  (Cloud device API `/sync` with the `CHECKOUT_API_KEY` secret, which also does captures; payment timeout at least
  160 s), `PAYMENTS_APP` (Tap to Pay through the Adyen Payments app, shared key; POIID is the boarded installation ID),
  or the simulator. Cloud and Payments app are only offered off-terminal; Automatic stays the simulator there.
- The environment is still never a setting: the terminal certificate, else the endpoint that accepts the cloud API key
  (`CloudDevices.detect`: TEST, then the device country's live data centre, then the others), else the installed
  Payments app package (both installed is a setup problem). Changing the mode clears the stored environment.
- The Payments app takes only payments and reversals: no print, abort or diagnosis (the connection check only checks the
  setup), no printer; status checks are answered only from answers that arrived with no exchange waiting
  (`AppLinkExchange.lateReplies`). `PaymentsAppBridge` is the activity's side; coming back without an answer makes the
  outcome unknown. The Payments app API key (`PAYMENTS_APP_API_KEY`) boards and revokes it (`TapToPaySetup`).
- Every sale is written as PENDING before the terminal is called; interrupted ones become UNKNOWN at startup. Without
  a response after the timeout (default 120 s) the client polls the status every 5 s while it is `InProgress`.
- Secrets (shared-key passphrase, Checkout API key, SMTP password, PIN verifier) live only in `SecretStore`; never log
  or persist them in plain text.

## Conventions
- User-facing English is US English (strings, receipt label defaults, simulator texts, messages that reach the screen,
  README and `docs/`). Unchanged on purpose: identifiers and resource names (`preAuthorisation`), stored values
  (`SaleKind.PRE_AUTHORISATION`), Adyen's field names and texts, and "Harbour Coffee Co.".
- Languages: English, Simplified Chinese (`values-zh-rCN`) and Japanese (`values-ja`); `locales_config` exposes
  per-app language choices on Android 13+. Keep resource keys and format arguments covered in both translations
  (Chinese/Japanese plurals use only `other`). Localize new-install receipt/email defaults, never stored merchant text
  or imported catalogues. Receipt/email labels are read again at delivery; Adyen receipt fields stay verbatim.
  Japanese receipts add per-rate taxable totals; plain-text receipts count wide CJK glyphs as two columns. Customer
  Area menu paths (Devices › Device settings, Payments › Payment list, …) stay in English in strings and docs; zh-CN
  quotes UI names with “”, ja with 「」.
- KDoc on every public or protected declaration (tests exempt): units, `null` meaning, threading, `@throws`, formats;
  never restate the name. detekt's `OutdatedDocumentation` wants, once a class KDoc has constructor tags, one tag per
  constructor parameter in order: `@property` for public properties, `@param` for the rest (a `private val` is a
  `@param`). Check KDoc claims against the code.
- Compose: `LongMethod` (60 lines) and `CyclomaticComplexMethod` apply to composables; split into private composables
  and `ColumnScope`/`RowScope` extensions, pass state and callbacks (never the view model), name event lambdas in the
  present tense (`onSkuScan`).
- Sizes come from `LocalDimens` tiers (`Compact` < 360 dp wide or 520 dp tall, `Medium` < 640 dp, else `Regular`);
  prefer `Dimens` fields over hard-coded values. Primary actions go in
  `MiniScaffold(bottomBar = { BottomActions { … } })`. Reuse `ui/components` (buttons, search, `OutcomeHeader`,
  `TransactionRow`, `Keypad`, …); result screens put `HomeButton` beside their primary action.

## Tests
- Robolectric at SDK 33 with `TestApplication`; Compose tests use the v2 rule and `en-rAU`. `SmallScreenTest` checks
  primary actions are visible without scrolling at `w320dp-h460dp-hdpi` (AMS1), plus P630 and S1F2 sizes.
- `LocalizationTest` checks translation/format parity and receipt defaults and writes sample previews under
  `app/build/reports/localization/`; `LocalizedUiTest` checks Chinese/Japanese checkout at AMS1 size.
- `TestEnvironment(device = FakeDevice(detectedPoiId = …), terminal = FakeTerminal())` plays a terminal; `FakeCloud`,
  `FakePaymentsApp` (simulator behind encrypted App Links) and `FakeManagement` play the cloud, the Payments app
  (`FakeDevice(paymentsApps = …)`) and its boarding; it is a rule
  declared `@get:Rule(order = 0)` before the compose rule (`order = 1`). Call `container.start()` for the background
  connection check. No network or DNS in tests (give `TerminalHttpClient` a fake `Dns`).
- In Compose tests wait with `compose.awaitCondition`, never `await` (it blocks the main looper and deadlocks on CI);
  after a save, wait for the editor to close. Transfer import view-model tests run the main looper while waiting
  (`TransferViewModelsTest.settled`). Busy cores (a dozen `yes > /dev/null`) reproduce CI-only timeouts.
- Under Robolectric, Compose never idles when an `AlertDialog` (or a platform-default-width `Dialog`) contains a text
  field: test that content as its own composable (`TaxRateForm`/`TaxRateFormTest`).

## Product decisions (deliberate; do not "fix")
- API keys on tablets and phones (cloud, Tap to Pay) go against Adyen's advice to keep keys on a server; the app has no
  backend by design, so the docs and SECURITY.md say so and recommend a terminal on the network. The Mobile SDK (card
  readers) is deliberately not integrated: it needs a backend for `/auth/certificate`, a private Maven repo, PCI MPoC and
  six-monthly updates.
- Not verified against Adyen yet (no test account in CI): the cloud's event notifications for an offline or busy
  terminal, and the Payments app's return URL encoding, `error` answers and size limits on a real phone.
- No TEST banner (test terminals show TEST themselves); `ModeBanner` only for the simulator. No "settings not
  protected" warning and no terminal/printer status line on Home (they are in Settings › About); Products/Settings
  are slim grey buttons. Secret field placeholders stay one line ("Type to replace").
- There is no TEST/LIVE setting: the environment comes from the terminal certificate and picks the Checkout API
  endpoint. Partly entered Checkout API setup fails visibly instead of falling back to the Customer Area.
- Every product has a tax rate (0% rates for untaxed items; no "tax applies" switch); "Charge tax" off taxes nothing
  but keeps rates. The last tax rate cannot be deleted.
- The customer reference is asked for exactly when it is the shopper reference (no separate switch); with the email
  as shopper reference, email capture always includes "before payment".
- References: optional prefix, `yyMMdd-HHmmss-XXXX`, refunds `R-…`, cancellations `C-…`.
- Pre-authorisations: held payments are cancelled (a full reversal), not refunded, and count as "Held" in day totals.
  Tip on the receipt (only offered with a printer) sends a sale as a pre-authorisation; a tip above 20% of the bill
  is adjusted before capture, else overcaptured; a refused adjustment leaves the tip unsaved. Idempotency keys
  (`capture-{saleId}-{amount}`, `adjust-{saleId}-{heldBefore}-{amount}`) make retries safe.
- History search: every word must match (case-insensitive) a reference, auth code, shopper data, card last 4, brand or
  wallet, or be exactly the amount; combined with the filter chips, and day totals cover only what is shown.
- Setting up another terminal: one QR transfer with switches for catalogue, settings (minus the device fields of
  `AppSettings.withDeviceFieldsOf`, which each section names next to its fields) and secrets, sealed by `TransferSeal`
  with a 12-character code. What travels is `AppSettings.shared()` (taken over with `takingOver`), so a new settings
  section or field transfers by default; the import rules (currency after import, transfer code, skipped secrets) are
  `SetupTransfer`'s and `ReceivedTransfer`'s.

## Documentation
- Human docs: `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CONTEXT.md` (domain glossary), `LICENSE` (MIT). Keep
  feature claims in README, `docs/index.html` and `docs/getting-started.html` (Customer Area paths, Settings names) in
  sync with the app.
- `docs/` is the static GitHub Pages site (`https://astiskala.github.io/minimpos/`, no build step).
- Chinese/Japanese pages and guides are in `docs/zh-CN/` and `docs/ja/`, with reciprocal language switches and
  canonical/hreflang links. Check all six pages with `python3 docs/tests/test_site.py`. Marketing screenshots use
  original AMS1/S1F2-style SVG illustrations: keep bottom bezels blank and the AMS1 top free of an NFC symbol.
  Screenshots match their frame's screen (S1F2 9:16 at 540×960, AMS1 3:5 at 480×800). The language switch is a
  script-free `<details>` globe menu at the top right of the header.
  The demo screens remain English, visibly disclosed. `social.png` (1200×630) uses the same blank terminal frames.
  Guides explain receipt-language settings, the physical-printer check, and the limits: not Chinese tax invoices
  or guaranteed Japanese qualified invoices.
