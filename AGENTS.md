# Mini mPOS

Android POS app that runs on Adyen Android payment terminals and takes payments through the local Terminal API
(`https://localhost:8443/nexo`) on the same device.

## Modules
- `:core` – pure Kotlin: money/tax maths, cart, refund apportioning, receipt document model + HTML/plain renderers
  (pre-authorisation and cancellation receipts too; `SaleReceipt.tip` = `TipLines.Blank` prints AMOUNT plus write-in TIP
  and TOTAL rows, and a SIGNATURE row on the merchant copy, `TipLines.Entered` the tip and total; `heldNow`/`captured`
  add a pre-authorisation's adjusted and captured amounts), compact transfer codec `TransferCodec` (binary + raw
  deflate + Base45, chunked into `MPC1:` QR codes; a `Transfer` holds an optional catalogue, settings text and
  `SealedSecrets`, all opaque to core; v4 starts with a sections varint (bit 0 catalogue, 1 settings, 2 secrets) and
  stores the last two length-prefixed; a catalogue alone is still written as v3, which has a per-product flags varint,
  bit 0 = pre-authorisation; v1/v2 still decode as sale products), refund QR payload (`MPR1*…`),
  Adyen currency table (`AdyenCurrencies`, Adyen's decimals win over ISO, e.g. ISK=2, IDR=0; search and device-country
  default). Any of its 138 currencies can be configured; there is deliberately no country/region setting (blank currency
  follows the device's country, else EUR).
- `:terminal-api` – built on the official `com.adyen:adyen-java-api-library`: nexo models, `TerminalLocalAPI`
  (encryption via `NexoCrypto`), `TerminalCommonNameValidator`, `SaleToAcquirerData`/`ApplicationInfo`. Our code:
  `TerminalTls` (trusts both bundled Adyen terminal fleet roots; the root a chain anchors to must match the leaf's
  `*.test|live.terminal.adyen.com` name, and that environment is reported via `onEnvironment` – there is no
  TEST/LIVE setting), `TerminalHttpClient` (OkHttp `ClientInterface`),
  `AdyenLocalTransport`, `TerminalClient` (payment/reversal/print/diagnosis/abort with transaction-status recovery),
  `RetryAdvice` (Adyen's declined-payment tables), in-process `TerminalSimulator` (round-trips through the library's Gson).
  `PaymentParams.preAuthorisation` sends `SaleToAcquirerData.authorisationType=PreAuth` plus
  `additionalData.manualCapture=true` (Adyen's JSON form); the simulator labels those receipts, returns an
  `adjustAuthorisationData` blob for approved ones (as with "return adjust authorisation data") and answers a full
  reversal of one as a cancellation. Package `checkout`: `PaymentModifications` (capture, `amountUpdates` with reason
  `DelayedCharge`, synchronous when the blob is sent, and `verify` via `/paymentMethods`), `CheckoutModifications`
  (Checkout API v72 posted directly with OkHttp and Gson, `x-api-key` and `Idempotency-Key` headers; the library's
  Checkout models need Jackson and keep rules for hundreds of classes, so they are not used; 4xx except 408/429 and
  unsent requests are `NotProcessed`, timeouts/408/429/5xx `Unknown`), `CheckoutCredentials` (TEST
  `checkout-test.adyen.com/v72`, LIVE `{prefix}-checkout-live.adyenpayments.com/checkout/v72`; `toString` hides the
  key). `simulator.SimulatedModifications` accepts everything (authorised with a new blob when one was sent). The
  `ArchitectureTest` lets only `transport` and `checkout` use OkHttp.
  Its interface uses only its own types (`PrintJob`/`PrintLine`, `RecurringModel`, `TransactionKind`,
  `ApplicationSummary`); the nexo mapping (document qualifiers, URL-encoded QR contents) stays inside, and `:app`'s
  `ArchitectureTest` forbids `com.adyen` in the app.
- `:app` – Compose UI (Material 3, Navigation 3), Room, DataStore (JSON), Keystore-encrypted secrets, CameraX + ZXing
  scanning (no Google Play services on terminals), JavaMail SMTP (`com.sun.mail:android-mail`), manual DI in `AppContainer`.

## Build and verify
- JDK: the Gradle daemon auto-provisions Temurin 21 (arm64) via `gradle/gradle-daemon-jvm.properties`; any JDK 17+ can launch the wrapper.
- Full gate (run after changes): `./gradlew qualityGate` – Spotless/ktlint, detekt (`config/detekt/detekt.yml`, plus
  `compose.yml` with the Compose rules for `:app`; no baseline), the Dokka KDoc link check
  (`dokkaGeneratePublicationHtml` in each module's `check`, Dokka 2.2 with `failOnWarning` and every visibility
  documented, so an unresolved `[link]` anywhere fails; `:app` is documented from its release variant), Android Lint
  in every module (`checkAllWarnings`, test sources, warnings are errors), unit + Robolectric/Compose UI tests, ArchUnit
  `ArchitectureTest` in each module,
  Kover thresholds (core ≥95% line/85% branch, terminal-api ≥90%/75%, app non-UI ≥80%), and
  `verify{Debug,Release}TerminalManifest`. CI (`.github/workflows/ci.yml`) runs it plus `:app:assembleRelease`;
  Dependabot (`.github/dependabot.yml`) has a 7-day cooldown and leaves `com.adyen` ungrouped.
- Security advisories on transitive dependencies (GitHub's automatic dependency submission resolves the whole build,
  so alerts also cover AGP's, Android Lint's and Dokka's own libraries, all reported against `settings.gradle.kts`):
  Dependabot cannot fix those (its security update fails with `dependency_not_found`). Add the patched version, at
  least 7 days old, to the `patched` table in `settings.gradle.kts`, which raises older versions of the release line
  in every configuration and build script classpath; then check `./gradlew <module>:dependencies buildEnvironment`
  and run the gate plus the signed release build. Drop entries once upstream has caught up.
- Versions: `version.properties` (root) holds `versionName`/`versionCode`, read by `app/build.gradle.kts`. Releases
  come only from `.github/workflows/release.yml` (manual, on `main`, input patch/minor/major): it raises the
  version (versionCode +1), runs the gate, builds the APK signed with secrets `RELEASE_KEYSTORE` (base64 JKS) and
  `RELEASE_SIGNING_PROPERTIES` (the non-path lines of `keystore.properties`, verbatim) from the `release` environment
  (main only), then commits "Release X.Y.Z" as Adam Stiskala <github@adamstiskala.com>, tags `vX.Y.Z`, pushes both
  atomically and publishes a GitHub Release with `minimpos-X.Y.Z.apk`. Never bump the version by hand in a change.
  Commits in this repo use that same identity (not the Adyen work email) and carry no bot attribution.
- detekt on `:app` runs per variant (`detektDebug`, `detektDebugUnitTest`, and the plain `detekt` task that `check`
  adds). `app/build.gradle.kts` gives each variant task its compilation's classpath plus javac's output (BuildConfig);
  the plugin's own wiring misses BuildConfig and triggers a Gradle 10 deprecation. detekt does not run compiler
  plugins, so in `:app` main code get serializers with `serializer<T>()`, not the plugin-generated `T.serializer()`
  (it shows as "compiler errors found during analysis"). The build output should stay free of warnings.
- VS Code: the committed `.vscode/settings.json` turns off the Java extension's Gradle import, which fails on Gradle 9
  parallel builds (eclipse.jdt.ls#3505) and, before the wrapper checksum is trusted, falls back to its bundled Gradle.
- ktlint caches `.editorconfig` in the Gradle daemon: after editing it, run `./gradlew --stop` before `spotlessCheck`.
- Room's generated Kotlin trips the compiler's extra `CAN_BE_VAL` and `REDUNDANT_VISIBILITY_MODIFIER` warnings, so
  `:app` turns those two off; detekt's `VarCouldBeVal` and `RedundantVisibilityModifier` cover hand-written code.
- Format: `./gradlew spotlessApply`. Release APK: `./gradlew :app:assembleRelease` (unsigned unless `keystore.properties`
  exists with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; never commit it). The maintainer's upload key is
  `~/.android/minimpos-release.jks` (alias `minimpos`, outside the repo); signed output is `app-release.apk`, v2 only
  (AGP drops v1 at minSdk ≥ 24 even with `enableV1Signing`; Adyen recommends v2 for minSdk > 23).
- Kotlin warnings are errors in every module.
- Dead-code audits: detekt only catches unused private code. After `:app:assembleRelease`, the first block of
  `app/build/outputs/mapping/release/usage.txt` (R8's initial tree shaking, before any `androidx.*` entries) lists
  public members of all three modules that production never reaches; later blocks are inlining noise. A removed getter
  whose field is still read inside the class is not dead. `@Serializable`/Room types are kept by rules, so check their
  members by hand.

## Adyen terminal constraints (enforced by `VerifyTerminalManifestTask`)
- minSdk 28 (S1F2, Android 9, is the lowest in Adyen's app requirements); no CATEGORY_HOME, no `testOnly`.
- Only allowlisted permissions: the app uses INTERNET, ACCESS_NETWORK_STATE, CAMERA. The androidx
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is not on the allowlist, so `StripManifestPermissionsTask` removes it
  from each variant's merged manifest (`tools:node="remove"` would warn on every unit-test manifest merge).
- No adb on terminals: test on an emulator/phone with Settings › Terminal › "Payments go to" = Simulator (the default
  when not on a terminal), or "A terminal on your network" with its LAN IP, POIID and shared key.
- The POIID comes from `Settings.Global.DEVICE_NAME` on the terminal. When it is detected, that POIID and `localhost`
  always apply and the POIID/IP fields are hidden; they are only asked for (and used) off-terminal. Only the shared key
  is entered on a terminal (inline passphrase field; Done or "Save and test" stores it, reads it back and runs a
  diagnosis, shown in a dialog). "Payments go to" offers two choices; picking the device's default stores `AUTO`.
- The target small screen is the AMS1: 4" 480×800 px at hdpi, ~320×460 dp after the status bar and Adyen's nav bar.
  AMS1 runs Android 10 and has no printer.
- Upload a signed APK in the Customer Area; each upload needs a new `versionCode` (the Release workflow raises it
  every time). Adyen does not sign it for you: an
  unsigned `app-release-unsigned.apk` uploads and "converts" fine, then fails on the terminal with
  `INSTALL_PARSE_FAILED_NO_CERTIFICATES` ("none of the signatures belongs to a trusted signer"). Always sign with the
  same key: the Customer Area rejects a version signed differently from earlier uploads of the package with
  `INVALID_SIGNING_CERTIFICATE_MISMATCH`, and the only fix is to have all earlier versions deleted (Adyen Support).
- `applicationId` is `io.github.astiskala.minimpos` (from versionCode 5); the Kotlin package and `namespace` stay
  `io.minimpos.app`. The Customer Area still holds the abandoned `io.minimpos.app` (versions 1–2 unsigned, 3 rejected);
  never reuse that ID or change this one, since each package is tied to the signing key of its first upload.
- The application icon (`android:icon` on `<application>`) is PNG-only (`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`,
  rendered from `docs/favicon.svg`): the Customer Area cannot render adaptive/XML icons and shows a blank placeholder.
  Never add an adaptive `ic_launcher` (with one present, `aapt2 dump badging` resolves every density to the XML even
  with PNG fallbacks); `VerifyTerminalManifestTask` enforces this. Launchers, though, shrink a bitmap icon into a filler
  circle/box (black on Adyen terminals), so `MainActivity` has its own adaptive icon, `@mipmap/ic_launcher_adaptive`
  (`mipmap-anydpi`: `ic_launcher_background` colour, `drawable/ic_launcher_foreground` and `ic_launcher_monochrome`
  vectors, whose paths are copied from `docs/favicon.svg` – already on the 108-unit grid). Badging then shows
  `application-icon-*` as PNGs and only `launchable-activity` as the XML.

## Adyen Java library on Android
- Always set our `TerminalHttpClient` on the `Client`: the library's default Apache HttpClient 5 crashes on Android.
  Careful in Kotlin: inside `Client(...).apply { }`, a bare `httpClient` resolves to `Client.getHttpClient()` (which
  creates the Apache client); that is why the transport's parameter is called `http`.
- `TerminalLocalAPI` always posts to `<terminalApiLocalEndpoint>:8443/nexo/`, so there is no port setting.
- nexo models use `XMLGregorianCalendar`; Android has no `DatatypeFactory`, so `xerces:xercesImpl` (without
  `xml-apis`) is a runtime dependency of `:terminal-api`, kept by R8 (`app/proguard-rules.pro`).
- The library ships no R8 rules: keep `com.adyen.model.{nexo,terminal,applicationinfo}` and the serialisation classes.
- Unknown enum values in responses deserialise to null (not an error); `ErrorCondition` is exposed as its string value.
- `app/lint.xml` ignores `TrustAllX509TrustManager` for the Adyen jar only: it comes from the library's TEST-only
  `TerminalLocalAPIUnencrypted`, which we never use and R8 strips (check the release dex if the library is upgraded).
- The unminified debug APK is ~90 MB (the whole library is dexed); the R8 release APK is ~6.5 MB.
- Application info (`PosApplication` in `AppContainer`) goes on every payment and refund; values are cleaned to Adyen's
  rules (≤40 chars, starting alphanumeric) and must stay identically formatted across requests.

## Documentation and website
- Human docs: `README.md`, `CONTRIBUTING.md`, `SECURITY.md` (GitHub private vulnerability reporting), `LICENSE` (MIT).
  Repository: `github.com/astiskala/minimpos`. Keep feature claims in README, `docs/index.html` and the setup guide
  `docs/getting-started.html` (Customer Area paths, Settings names, pre-authorisation setup) in sync with the app.
- `docs/` is the GitHub Pages site (static HTML/CSS, no build step, `.nojekyll`); enable Pages from `main` › `/docs`.
  It is served at `https://astiskala.github.io/minimpos/`; `docs/images/social.png` is the Open Graph image.
- Screenshots in `docs/images/` are 540×1170 PNGs with a 96-colour palette (Pillow: Lanczos resize, median-cut, no
  dither; this ImageMagick has no PNG delegate), captured at 1080×2340 on AVD `MiniMpos_Docs_Pixel4a` (API 33
  google_apis, `pixel_4a` profile, gesture nav, `hw.keyboard=no`). Setup: `adb root`, `setprop persist.sys.locale en-AU`
  and Australia/Perth (`settings put global auto_time_zone 0` and `service call alarm 3 s16 Australia/Perth`; setprop
  alone does not stick), then `stop`/`start`; window, transition and animator animations off; SystemUI demo
  mode (clock 09:30, full Wi-Fi and battery, no notifications). Demo café data ("Harbour Coffee Co.", 1 Wharf Street
  Fremantle, ABN; GST 10% default and GST-free; AUD; 10 products in Coffee/Food/Retail, the beans GST-free with a
  barcode; plus "Catering deposit" $200.00 GST 10% in a Bookings category as a pre-authorisation product) is seeded
  before the first launch: a Room DB built from `app/schemas/.../6.json` (Python sqlite3,
  `PRAGMA user_version`) and `files/datastore/settings.json` (also auto-lock 10 min, simulator delay 6 s so the
  "waiting" screen can be captured), piped in with `adb shell "cat … | run-as io.github.astiskala.minimpos sh -c 'cat >
  …'"` (debug builds only). The admin PIN (1357) is set in the app. Flows: sale of 2 flat whites, banana bread and
  beans ($34.00, CUST-1042, save card; `checkout.png` shows Save card on and Tip on the receipt off) → printed receipt
  (simulated printer sheet, expanded and scrolled to the items), sales of $11.50 and $29.50, then an item refund of the
  first sale ($11.00) – these four can also be seeded into the DB to re-capture later screens only – then a tip on the
  receipt sale of 2 avocado toast and 2 flat whites ($38.00; `tip-receipt.png` is its merchant copy with the blank TIP,
  TOTAL and SIGNATURE lines in the expanded printer sheet, `tip.png` the Enter tip screen with Total $45.00 typed),
  confirmed so History shows Capture requested and Tips $7.00, then a pre-authorisation of the catering deposit
  (CUST-2077; `pre-auth.png` is the Pre-authorize screen before tapping it, `pre-auth-detail.png` its history detail
  with Capture, Adjust amount and Cancel pre-authorisation), left open so Home shows the split tile. `transfer.png` and `export.png` are Products › More › Share with another terminal (after the PIN is set, so passwords and keys are offered) before and after Show QR codes, paused on a code. Switches have no
  text in the `uiautomator` dump: tap the n-th `checkable` node. Drive the UI by finding nodes by text in
  `uiautomator dump` (dialogs are separate windows; tap keypad keys by their labels, and press Back twice to close the
  printer sheet, as the first only collapses it). `social.png` (1200×630) is an HTML page (navy background with a
  green glow, favicon + "Mini mPOS", "The whole checkout, on the payment terminal.", lead line, green footer, and
  `sale.png`/`receipt.png` in navy phone frames rotated −4°/6°) rendered with `chrome-headless-shell`. Use a separate
  AVD so existing emulator data is untouched, and shut emulators down with `adb shell reboot -p`: `adb emu kill` can
  leave a freshly installed APK corrupt.
- To render the site headlessly, Playwright's `chrome-headless-shell` (`~/Library/Caches/ms-playwright/`) with
  `--screenshot --window-size=W,H` works; full Chrome headless and the Playwright MCP browser crashed in this sandbox.
- AMS1-sized emulator: AVD `MiniMpos_AMS1_480x800` (API 33 google_apis arm64, "Nexus S" profile edited to
  480×800 / `hw.lcd.density=240`, `hw.keyboard=no`, `hw.mainKeys = no`). Run it headless with
  `emulator -avd MiniMpos_AMS1_480x800 -no-window -no-snapshot -gpu swiftshader_indirect -port 5560`. For a realistic
  48 dp nav bar run `adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton` and
  `... disable com.android.internal.systemui.navbar.gestural` (with both enabled the insets stay at 24 dp).
  `adb shell settings put global device_name AMS1-000168223606144` makes the debug app behave as on a terminal.

## Conventions
- All user-facing English is US English: `strings.xml`, the `:core` receipt label defaults, the simulator's receipt
  text, exception messages that can reach the screen, README, CONTRIBUTING, SECURITY and the `docs/` site (authorize,
  pre-authorization, catalog, canceled/canceling but cancellation, license, math). Unchanged on purpose: code
  identifiers and resource names (`preAuthorisation`, `CatalogueCodec`, `R.string.status_cancelled`), stored values
  (`SaleKind.PRE_AUTHORISATION` is persisted), Adyen's field names (`authorisationType`) and texts Adyen sends
  (refusal reasons such as "Cancelled by shopper", mirrored by the simulator), URLs, and the demo business name
  "Harbour Coffee Co.". KDoc and comments are not user-facing and may use either spelling.
- KDoc on every public or protected class, object (unnamed companions too), function, property and enum entry
  (detekt's `Undocumented*` with `searchProtected*`, `EndOfSentenceFormat` and `OutdatedDocumentation` rules; tests are
  exempt). Document units, `null` meaning, threading, `@throws` and Adyen or format details; never restate the name.
  Data classes use `@property` tags or per-property KDoc. `OutdatedDocumentation` is exhaustive and ordered: once a
  class KDoc has constructor tags, every constructor parameter needs one in declaration order, `@property` only for
  public properties and `@param` for the rest (a `private val` is a `@param`; `@property` on it is "not present").
  `[links]` must resolve (Dokka); a KDoc claim about behaviour must be checked against the code, not guessed. Private
  code gets a comment only where the reason is not obvious.
- detekt's `LongMethod` (60 lines) and `CyclomaticComplexMethod` apply to composables. Split screens into private
  composables; use `ColumnScope`/`RowScope` extensions for groups of siblings (the Compose rules allow multiple
  emitters there, and the parent's `spacedBy` keeps applying, unlike a wrapping `Column`), pass state and callbacks
  (never the view model: `ViewModelForwarding`), and name event lambdas in the present tense (`onSkuScan`).
- Stored transactions: `SaleRepository` (`container.sales`), `RefundRepository` (`container.refundRecords`) and
  `HistoryRepository` (`container.history`: the merged list, settling interrupted transactions at startup, pruning and
  clearing). Settings' test buttons get their services through `SettingsChecks`.
- Transactions: `container.payments` and `container.refunds` are both a `TransactionLifecycle` (one at a time, PENDING
  first, catch-all to UNKNOWN, outcome → `SettlementStatus`, `recheck` of UNKNOWN records for both kinds, cancel and
  busy-abort); a `TransactionBook` adapter (`SaleBook`, `RefundBook`) stores each kind.
- Refund rules live in `refund/RefundablePayment` (pure, plain JUnit tests): eligibility (approved, transaction ID, a
  time stamp `TerminalClient.instantOf` can read), what is left, pricing a `RefundChoice`, the `RefundStart` (full
  reversal only before any refund, "R" reference) and the receipt's refund QR code. Sale detail, the refund screen,
  `ReceiptFactory` and `RefundBook` all use it; do not re-derive refundability elsewhere. It also holds the
  rule for payments that only hold their amount (`PaymentHold.isHeld`: pre-authorisations and tip sales not yet
  captured): not refundable (`RefundInvalidReason.PRE_AUTHORISATION`/`AWAITING_TIP`) but `cancellation()` once (a full
  reversal of `heldMinor`, "C" reference, `RefundStart.cancellation`). Once captured they are refundable up to the
  capture (a captured pre-authorisation by amount only, and still without a refund QR code).
- Pre-authorisations: `SaleKind` (`SALE`/`PRE_AUTHORISATION`) on `products.kind` and `sales.kind`, and
  `refunds.cancellation` (DB v5, auto-migration 4→5 with column defaults). History's detail captures them
  (`Route.Capture`: capturing more than `heldMinor` adjusts first, less releases the rest) or adjusts them
  (`Route.Capture(adjustOnly = true)`, API only), and cancels them through the refunds' `TransactionLifecycle`. `container.preAuthSession` is a separate
  single-item `SaleSession` (`container.session(kind)`), so a sale being rung up survives. `Route.PreAuth` is
  `SaleScreen(preAuthorisation = true)`: only pre-auth products (`productBySku(sku, kind)`) and no cart at all (no
  checkout bar, cart sheet or panel, clear action or quantity badges); tapping a product, adding a custom amount or a
  found barcode pushes `Route.Checkout(true)` straight away (guarded by `navigator.current == Route.PreAuth` against
  double taps), and Back from checkout lets another choice replace the item. `Route.Checkout`/`Route.Payment` carry
  `preAuthorisation`, and `Route.ringUp()` picks where to return.
  Home splits the green tile into New sale + Pre-authorize only while a pre-auth product exists. Checkout's save-card
  default is `PaymentSettings.preAuthTokenizeDefaultOn` (default on). History: `HistoryFilter.PRE_AUTHS`, day totals
  count open pre-auths as "Held" (not sales), cancellations are neither refunds nor shown with a minus.
  `statusTitle(sale)`/`statusKind(sale)` show "Pre-authorized" / "Cancellation requested" (`refundedMinor > 0`).
- Setting up another terminal (user's design choices; `data/transfer/SetupTransfer`, screens `feature/transfer`,
  routes `TransferExport`/`TransferImport` behind the PIN, from Products' menu and Settings › Data): one combined
  transfer with switches for catalogue, settings and secrets. Settings travel as lenient JSON without defaults
  (`TransferredSettings`: `AppSettings` minus terminal mode, host, POIID, environment and simulator; everything else,
  printer settings, reference prefix and SaleID included) and replace the receiver's on import; the default tax rate
  travels by name and rate. Secrets (all four, the PIN as its verifier, checked by `PinManager.isValidVerifier`) are
  sealed by `data.security.TransferSeal` (PBKDF2-HMAC-SHA256, 150,000 iterations stored in the blob, AES-256-GCM) with
  a 12-character code from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` shown on the sender; without the code the rest imports.
  View-model tests of the import must run the main looper while waiting (`TransferViewModelsTest.settled`): `await`
  blocks it and stalls the import.
- Receipts: `container.receipts` (`ReceiptDelivery`) prints and emails by sale/refund ID and owns the post-transaction
  automation (the lifecycles `arm` it on success; result screens claim it once with `automationForSale`/`ForRefund`,
  which applies auto-print, auto-email and the merchant copy policy). Under "Only when a signature is needed" a sale
  awaiting its tip also makes the merchant copy due (the shopper signs it).
- Tipping on the receipt (user's design choices): checkout's "Tip on the receipt" switch (`CheckoutForm.tipOnReceipt`,
  default `PaymentSettings.tipOnReceiptDefaultOn`) is offered for sales only while `printerAvailable`; such a sale
  (`sales.tipOnReceipt`) is sent as a pre-authorisation (PreAuth + manual capture). Printing follows the normal
  auto-print/merchant-copy settings. The tip is entered on `Route.Tip` (from the result screen, or History's detail and
  its `AWAITING_TIP` filter) as the tip or the total, or "No tip"; it is final once confirmed. `Captures.addTip`
  adjusts first when the tip is above `PaymentHold.TIP_ADJUSTMENT_PERCENT` (20%, Adyen's rule of thumb; otherwise
  overcapture, which Adyen Support must enable); a refused or failed adjustment leaves the tip unsaved. Day totals count
  tips (`DayTotals.tipsMinor`), status titles say "Awaiting tip" / "Capture requested" / "Capture in Customer Area".
- Captures (`payment/Captures`, rules in `refund/PaymentHold`): through the optional Checkout API (`terminal/AdyenApi`:
  `TerminalSettings.merchantAccount`/`liveUrlPrefix`, `Secret.CHECKOUT_API_KEY`, environment from the terminal
  certificate; simulated with the simulator), else recorded as `CaptureStatus.MANUAL` for the Customer Area. Anything
  of the API entered means API mode, so a half setup fails visibly (`TerminalState.captureMode`/`apiProblem`). DB v6
  (auto-migration 5→6) adds `sales.tipOnReceipt`, `tipMinor`, `authorisedMinor`, `adjustment`,
  `adjustAuthorisationData` (from the payment response, then each synchronous adjustment), `capturedMinor`,
  `captureStatus` (PENDING before the call, interrupted ones become UNKNOWN at startup) and `modificationMessage`.
  Idempotency keys are `capture-{saleId}-{amount}` and `adjust-{saleId}-{heldBefore}-{amount}`, so retries
  (`PaymentHold.canRetryCapture`) never act twice. `SaleEntity.amountMinor` is the amount as it stands (capture, else
  bill + tip, else what a pre-authorisation holds) for lists, refunds and refund QR codes.
- Fix findings instead of silencing them: no new `@Suppress`, lint ignores, baselines or rule exclusions. The existing
  ones are deliberate and commented: `TooGenericExceptionCaught` where the Adyen API throws `Exception` and where the
  payment/refund jobs must never leave a sale in progress; the Compose relaxations in `config/detekt/`; the lint
  ignores in `app/lint.xml` (third-party jars, the PNG launcher icon, `DuplicateStrings`); lint's online version checks;
  `TypographyDashes` on the POIID example.
- Never log or persist secrets in plain text: shared-key passphrase, Checkout API key, SMTP password and the PIN
  verifier live in `SecretStore` (AES-GCM, Android Keystore). Non-secret settings are `AppSettings` in DataStore.
- Every sale is written as PENDING before the terminal is called; interrupted ones become UNKNOWN at startup and can be
  re-checked (TransactionStatus) from history. Without a response after the payment timeout (default 120 s, Adyen's
  advice for local integrations) the client polls the status every 5 s, and keeps polling while it is `InProgress`.
- Receipts: the terminal's own printing is suppressed (`tenderOption=ReceiptHandler`); the app prints one combined slip
  (header, items, tax, Adyen card receipt lines, footer) plus the refund QR code as a second back-to-back print request.
- Do not call suspending side effects inside `MutableStateFlow.update {}` (it retries on contention and repeats them).
- Writes a screen starts (saving a product, a setting, a secret, the PIN, a tax rate, an import, clearing history) go
  through `launchWrite`/`persisting` (`feature/ViewModelWrites.kt`), never a bare `viewModelScope.launch`: leaving the
  screen cancels `viewModelScope`, which would silently drop a write still under way. The write always finishes; the
  follow-up (navigating back, updating the screen) runs only while the view model is in use. The product editor also
  ignores a second Save/Delete while one is running (`ProductEditUiState.saving`).
- Robolectric tests run at SDK 33 with `TestApplication`; UI tests use the v2 compose rule and `en-rAU` qualifiers.
  `SmallScreenTest` runs at `w320dp-h460dp-hdpi` (AMS1), with some flows also at `w320dp-h456dp-mdpi` (P630) and
  `w360dp-h568dp-xhdpi` (S1F2), and asserts primary actions are displayed without scrolling. Robolectric has no
  system bars, so qualifiers give the size between them (what `Dimens.forWindow` is chosen from on a device).
  `TestEnvironment(device = FakeDevice(detectedPoiId = …), terminal = FakeTerminal())` plays a terminal on the device
  (the simulator behind a passphrase check) instead of the real network transport; call `container.start()` to run
  the background connection check. `TestEnvironment` is also a rule: compose tests declare it `@get:Rule(order = 0)`
  before the compose rule (`order = 1`), so the view models are gone before its database closes (otherwise a late
  catalogue query fails a random test with "attempt to re-open an already-closed object"). Tests must not depend on
  the network or DNS (give `TerminalHttpClient` a `baseClient` with a fake `Dns` instead). In compose tests, wait for
  work a screen starts (saves resume on the main looper) with `compose.awaitCondition`, never `await` (it blocks the
  main thread, which deadlocks on slow machines such as CI), and after a save wait for the editor to close rather
  than for text the editor itself shows. Running a test class with all cores busy (e.g. a dozen `yes > /dev/null`)
  reproduces CI-only timeouts.
- Screen sizes: sizes come from `LocalDimens` (`ui/theme/Theme.kt`), chosen from the window less the system bars (the
  app is edge to edge): `Compact` below 360 dp wide or 520 dp tall (AMS1, P630), `Medium` below 640 dp (S1F2:
  360×568 dp), else `Regular` (S1E4/S1F4 Pro, phones). `compact` is true for Compact and Medium: it switches layouts
  (full-screen custom-item keypad, search behind an app-bar icon, no decorative icons); the sizes themselves differ
  per tier. Put a screen's primary action in `MiniScaffold(bottomBar = { BottomActions { … } })`; the scaffold pads
  content for the keyboard (`contentWindowInsets` ∪ IME) and hides the bottom bar while typing. Prefer `Dimens`
  fields (`cardPadding`, `titleStyle`, `outcomeStyle`, `amountStyle`, …) over hard-coded paddings, heights and styles.
- Shared UI (`ui/components`): `PrimaryButton` / `SecondaryButton` / `TertiaryButton` (text, same height as secondary)
  for every action; `OutcomeHeader` + `OutcomeNote` top every result and detail screen; `ProcessingContent` while the
  terminal works; `TransactionRow` for sales and refunds in lists; `QuantityStepper`; `Keypad` (amounts via
  `NumericKeypad`, and the PIN pad); `ScanHint` over camera views (`ScannerView` is always dark). Result screens put
  Home (`HomeButton`) beside their primary action.
- Terminal: `container.gateway` (`TerminalGateway`) resolves where payments go and runs every Terminal API operation;
  missing setup is never thrown but reported in the outcome (`setupProblem` is the one readiness rule). It remembers
  the certificate's environment and, per POIID, whether the terminal has a printer. `container.terminalStatus`
  (`TerminalStatus`) publishes one `TerminalState` (mode, POIID, setup problem, connection, printer availability,
  environment) for Home, Settings and the receipt screens; `start()` runs a debounced diagnosis when terminal settings
  or the passphrase change and persists a detected environment to `TerminalSettings.environment` (display only, e.g.
  the TEST banner).
- Home shows only the tiles, plus the setup/connection card when payments cannot work. By the maintainer's choice there
  is no "settings not protected" warning and no terminal/printer status line: where payments go, the POIID,
  environment and printer are in Settings › About. The passphrase's Saved/Not saved yet status is its own line under
  the field, at the screen's text edge with an icon (not the field's indented supporting text).
- `SecretStore.set` runs the Keystore off the main thread and throws `SecretStoreException` when encryption fails;
  `SettingsViewModel` reports it (`secretError`) instead of crashing. A stored secret that no longer decrypts gives a
  distinct "could not be read" configuration error.
- Under Robolectric, Compose never idles once a dialog at the platform default width (any `AlertDialog`, or a `Dialog`
  without `DialogProperties(usePlatformDefaultWidth = false)`) contains a text field. Dialogs with
  `usePlatformDefaultWidth = false` (custom item, currency picker) test fine; for an `AlertDialog`, put the content in
  its own composable and test that directly (see `TaxRateForm` / `TaxRateFormTest`).
- Tax rates (name + rate, up to 3 decimals), the tax-inclusive/exclusive setting and the "Charge tax" switch live in
  Settings › Tax; the last remaining rate cannot be deleted. Every product and custom item has a tax rate
  (`ProductEntity.taxRateId` is NOT NULL since DB v4); items without tax use a 0% rate such as "Zero rated" – there is
  deliberately no "tax applies" switch. `TaxRateRequiredMigration` (3→4, hand-written, in `AppDatabase.migrations`)
  moved untaxed products to the first 0% rate or a new "Zero rated". With "Charge tax" off,
  `Cart.totals(mode, chargeTax = false)` maps lines to `AppliedTax.NONE` and taxes nothing, but each product keeps its
  rate. Catalogue QR format v2/v3 can still carry untaxed products (from older builds); import gives them a 0% rate,
  and v1 and v2 codes still decode (as sale products). Older app versions cannot read v3 codes.
- The reference prefix defaults to empty (references like `260930-145811-VQ45`, refunds `R-…`, cancellations of
  pre-authorisations `C-…`). When the shopper reference comes from the email, `PaymentSettings.effectiveEmailCapture`
  always includes "before payment" (Never → Before, After → Both), and Settings only offers the "before" choices.
- The customer reference is asked for exactly when it is the shopper reference (`PaymentSettings.asksCustomerReference`,
  "Shopper reference comes from" = Customer reference), so cards can always be saved; there is deliberately no "Ask for
  a customer reference" switch (the old `askCustomerReference` key in stored settings is ignored). With the email as
  shopper reference, checkout neither shows the field nor sends or stores a customer reference.
