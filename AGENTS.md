# Mini mPOS

Android POS app for Adyen Android payment terminals; it takes payments through the local Terminal API
(`https://localhost:8443/nexo`) on the same device. KDoc in the code is the detailed reference; this file holds what
the code does not tell you.

## Modules
- `:core` – pure Kotlin: money/tax, cart, refund apportioning, receipt document model and renderers, QR formats
  (`TransferCodec` `MPC1:`, refund `MPR1*`), Adyen currency table (Adyen's decimals win over ISO), `PaymentMethods`.
  There is deliberately no country/region setting (blank currency follows the device's country, else EUR).
- `:terminal-api` – wraps `com.adyen:adyen-java-api-library` (nexo models, `TerminalLocalAPI`, `NexoCrypto`). Packages,
  layered downwards: `simulator` → `client` (`TerminalClient`), `checkout` (Checkout API v72 posted with OkHttp + Gson,
  because the library's Checkout models need Jackson and keep rules for hundreds of classes) → `transport`
  (`TerminalTls`, OkHttp) → `parse`. Its interface uses only its own types (`PrintJob`, `ReceiptField`, …); it knows
  neither Android nor `:core`. The in-process `TerminalSimulator` mirrors Adyen's behaviour (pre-auth blobs,
  `InProgress` status, cancellations) and shares a ledger with its `SimulatedModifications`.
- `:app` – Compose (Material 3, Navigation 3), Room, DataStore (JSON), Keystore-encrypted secrets, CameraX + ZXing (no
  Google Play services on terminals), JavaMail, manual DI in `AppContainer`.

## Build and verify
- Run `./gradlew qualityGate` after every change: Spotless/ktlint, detekt (no baseline), Dokka with `failOnWarning`
  (every `[link]` in KDoc must resolve), Android Lint (warnings are errors), unit/Robolectric/Compose tests, ArchUnit
  `ArchitectureTest` in each module, Kover thresholds (core 95/85, terminal-api 90/75, app non-UI 80 line/branch %),
  and `verify{Debug,Release}TerminalManifest`. Kotlin warnings are errors; the build output stays warning-free.
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
  entered. There is no adb on terminals: test on an emulator with "Payments go to" = Simulator (the default off a
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
- \* Only the `terminal` package talks to the terminal; `com.adyen` stays in `:terminal-api`; Room stays in `data`;
  crypto stays in `data.security`; nothing logs or prints.
- \* Stored sales change only through `SaleRepository`'s named transitions (`markSending`, `settle`, `recordAdjustment`,
  `recordCapture`, `modificationFailed`, `markEmailed`, `applyRefund`), refunds only through `RefundRepository.settle`;
  `HistoryRepository` does whole-table housekeeping. Tests write a stored state with `container.database.saleDao()`.
- \* Where a stored sale stands is `refund/PaymentStanding` (`sale.standing`); only it reads `captureStatus` and
  `holdCancelled`. What can be done with a payment is `StoredPayment.actions`
  (`container.storedPayments.observe(saleId)`); `Captures` checks against the same before sending.
- \* Pure decision rules (plain JUnit tests, no Android, coroutines, repositories or clocks): `payment/Checkout`,
  `refund/PaymentStanding`, `refund/RefundablePayment`, `feature/history/HistorySearch`, `terminal/TerminalSetup`.
  Do not re-derive refundability, standing, checkout rules or terminal readiness elsewhere.
- \* `TerminalSetup.resolve` is the one reading of where payments go (mode, POIID, host, setup problem, `apiSetup`,
  printer availability), called only by `TerminalSetupSource`, which the container hands to `TerminalGateway`,
  `AdyenApi` and `TerminalStatus`. `Captures` take a `suspend () -> ApiTarget`, not `AdyenApi`; `AdyenApi` takes the
  simulator's modifications, not the gateway. Missing setup is reported in outcomes, never thrown.
- \* `TransactionLifecycle` (payments and refunds: PENDING first, one at a time, recheck, abort) stores only through a
  `TransactionBook` (`SaleBook`, `RefundBook`).
- \* Screens get a transaction's receipt only through `feature/TransactionActions` (offer, print, email, recheck,
  automatic delivery for a fresh transaction), backed by `ReceiptDelivery`; only `ReceiptDelivery` uses
  `ReceiptFactory`, and only the container `arm`s automatic delivery. UI states other than Settings hold no
  `AppSettings` or `printerAvailable`.
- \* Outcomes are typed (`ActionOutcome`) and worded only in `feature/OutcomeMessages.kt`; tests assert outcomes, not
  strings.
- \* Terminal receipt fields become core receipt lines only in `ReceiptLinesJson`; print jobs are built only by
  `PrintRenderer` (a 1:1 map of `ReceiptDocument.segments()`).
- The terminal's own receipt printing is suppressed (`tenderOption=ReceiptHandler`): the app prints one combined slip
  (header, items, tax, Adyen's card receipt lines, footer), with the refund QR code as a second print request.
- One `SaleSession` per `SaleKind` (`container.session(kind)`); the payments' lifecycle clears it once approved.
- Settings ranges live on each settings section's companion; `SettingsRepository` normalises on every read and write,
  so nothing downstream clamps again.
- Writes a screen starts go through `launchWrite`/`persisting` (`feature/ViewModelWrites.kt`), never a bare
  `viewModelScope.launch`, so leaving the screen cannot drop them. Never call suspending side effects inside
  `MutableStateFlow.update {}`.
- Every sale is written as PENDING before the terminal is called; interrupted ones become UNKNOWN at startup. Without
  a response after the timeout (default 120 s) the client polls the status every 5 s while it is `InProgress`.
- Secrets (shared-key passphrase, Checkout API key, SMTP password, PIN verifier) live only in `SecretStore`; never log
  or persist them in plain text.

## Conventions
- User-facing English is US English (strings, receipt label defaults, simulator texts, messages that reach the screen,
  README and `docs/`). Unchanged on purpose: identifiers and resource names (`preAuthorisation`), stored values
  (`SaleKind.PRE_AUTHORISATION`), Adyen's field names and texts, and "Harbour Coffee Co.".
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
- `TestEnvironment(device = FakeDevice(detectedPoiId = …), terminal = FakeTerminal())` plays a terminal; it is a rule
  declared `@get:Rule(order = 0)` before the compose rule (`order = 1`). Call `container.start()` for the background
  connection check. No network or DNS in tests (give `TerminalHttpClient` a fake `Dns`).
- In Compose tests wait with `compose.awaitCondition`, never `await` (it blocks the main looper and deadlocks on CI);
  after a save, wait for the editor to close. Transfer import view-model tests run the main looper while waiting
  (`TransferViewModelsTest.settled`). Busy cores (a dozen `yes > /dev/null`) reproduce CI-only timeouts.
- Under Robolectric, Compose never idles when an `AlertDialog` (or a platform-default-width `Dialog`) contains a text
  field: test that content as its own composable (`TaxRateForm`/`TaxRateFormTest`).

## Product decisions (deliberate; do not "fix")
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
- Setting up another terminal: one QR transfer with switches for catalogue, settings (minus the device fields named by
  `TerminalSettings.withDeviceFieldsOf`) and secrets, sealed by `TransferSeal` with a 12-character code.

## Documentation
- Human docs: `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE` (MIT). Keep feature claims in README,
  `docs/index.html` and `docs/getting-started.html` (Customer Area paths, Settings names) in sync with the app.
- `docs/` is the static GitHub Pages site (`https://astiskala.github.io/minimpos/`, no build step).
