# Contributing to Mini mPOS

For a substantial change, open an issue first to agree on the approach. Keep pull requests focused, describe how you
tested them, and include before/after screenshots for UI changes. Contributions are licensed under [MIT](LICENSE).

## Build and run

You need JDK 17+ to start Gradle (the build downloads JDK 21), Chrome/Chromium for setup-helper browser tests,
Android SDK platform 37 and recent build tools.
Android Studio can install the SDK; otherwise set `sdk.dir` in an untracked `local.properties`. The full gate supports
macOS and Linux; its pinned Markdown/workflow linter binaries are not available for Windows.

```sh
./gradlew :app:installDebug
```

Run on an Android 9+ phone or emulator. Away from a terminal, the app defaults to the simulator. Set its outcomes,
delay and printer in Settings › Simulator. Adyen terminals have no adb: use the simulator for debugging or configure
a terminal on your network for integration testing. Follow the merchant [Quick start guide](https://minimpos.app/quick-start).

Android Studio is the simplest editor. The repository's VS Code settings disable the Java extension's Gradle import;
there is no Java code, and that importer cannot handle this build.

## Find the right code

[AGENTS.md](AGENTS.md#where-things-live) maps the modules. [CONTEXT.md](CONTEXT.md) defines domain terms and
decision owners. Read the relevant [module rules](app/AGENTS.md) ([Adyen module](adyen/AGENTS.md)) before changing a
module; their starred architectural rules are enforced by ArchUnit. Detailed contracts belong in KDoc, not a parallel
architecture manual.

On a terminal, the app sends encrypted Terminal API requests to `https://localhost:8443/nexo`. Network setups reach
the same API on another terminal; cloud setups use Adyen's HTTPS device API; Tap to Pay uses encrypted App Links to
the Payments app. Checkout handles captures, adjustments and links; Management boards the Payments app.
There is no backend. These are distinct transports, not interchangeable recovery policies.

## Verify a change

```sh
./gradlew spotlessApply
./gradlew qualityGate
```

For concise local or agent output, use the optional runner with the same Gradle tasks and flags:

```sh
scripts/gradle :core:test --tests '*MoneyTest'
scripts/gradle qualityGate
```

It uses plain console output and removes only known task/cache progress; warnings, unknown output, failure diagnostics
and the exit status survive. Every run saves complete stdout/stderr to a private log under ignored
`build/gradle-logs/` and prints its path. Failed runs also save a `repair.txt` beside it with the exit status, a quoted
rerun, failed tasks, assertions and source frames: navigation, not a replacement for the full log. Use regular
`./gradlew` for interactive progress or `--info`/`--stacktrace`.

### Fast iteration

`scripts/dev` selects existing tasks; it never changes the final gate. Run one Gradle invocation at a time per checkout.
Format after a coherent edit batch, not after every file write; never race formatting against edits.

```sh
scripts/dev preflight
scripts/dev test app '*CheckoutTest'
scripts/dev format
scripts/dev check architecture
scripts/dev finish
```

| Command | Use |
| --- | --- |
| `format` | Existing `spotlessApply`: ktlint and fixable Markdown, plus text whitespace. Review the diff. |
| `test MODULE PATTERN` | Matching tests in `core`, `adyen`, `app`, `website-test` or `tooling`; quote the pattern. |
| `check AREA` | Module `check` for `core`/`adyen`/`app`/`tooling`; focused `website`, `workflows`, `markdown`, `architecture`, `localization`, `ui`, `qr`, `db` or `payment` lanes. |
| `finish` | Format, then full `qualityGate --continue`; formatting failure stops the gate, gate failure stays nonzero. |
| `preflight` | Read-only check of JDK 17+, SDK 37/build tools and Chrome; reports missing cached linters without failing for them. |
| `warm` | Preflight, then compiler/test/browser/pinned-tool warmup; does not install SDKs or change config. |
| `report` | Build current tooling, then summarize existing JUnit XML: exact assertions, first project frame, quoted reruns and slowest suites. |
| `profile WORKERS [PATTERN]` | Rerun only the app test task with 1–4 workers and Gradle `--profile`; default pattern `*ui.*`. |
| `mutation` | Existing `mutationTest`: PIT mutation testing of `core`, `adyen` and `tooling`; slow, never part of `finish` or CI. |

Except `preflight`/`report`, commands accept trailing Gradle flags. `scripts/dev help` shows syntax.
Fast lanes deliberately omit unrelated checks. Passing one never replaces `finish`; dependency edits still need a
release build. For structural edits, run architecture tests and the affected type-resolved detekt task early
(`:app:detektDebug`, `:core:detektMain` or `:adyen:detektMain`).

On failure, read the repair packet and full log; `scripts/dev report` adds JUnit details. Group findings by root cause,
fix a batch, rerun affected checks, then finish on the final unchanged checkout. Reports are historical snapshots:
check their timestamps, especially after filtered tests or compilation failures. Missing reports are not success.

Compare repeated `scripts/dev profile 1 'PATTERN'` and `scripts/dev profile 2 'PATTERN'` runs on the same checkout
without concurrent builds. Gradle writes wall-time profiles under `build/reports/profile/`; summed suite times overlap
under parallel workers. `-PappTestWorkers=1` through `4` overrides the default two workers for experiments, not CI policy.
Keep the default unless repeated measurements justify a change. Inspect fixture startup/navigation/polling before
adding workers. For small filtered suites, try one worker: duplicated Robolectric/native startup can outweigh parallelism.
Reuse the daemon and caches; do not routinely run `clean` or `--rerun-tasks`.

Agent task recipes live in [minimpos-iteration](.devin/skills/minimpos-iteration/SKILL.md); concrete test starters in
[minimpos-tests](.devin/skills/minimpos-tests/SKILL.md). These point to existing owners and tests, not a second rulebook.

The gate checks:

| Check | Contract |
| --- | --- |
| Spotless/ktlint | Kotlin formatting; whitespace and final newlines in other text files. Restart Gradle after changing `.editorconfig`. |
| rumdl | Markdown rules and relative links; 120 columns outside tables/code. Wrap prose by hand; formatting keeps line breaks. |
| Website | Local links/fragments/assets, language and metadata parity, UI labels, screenshots, setup-helper fields and async generation; W3C Nu HTML/CSS validation with no messages. |
| Workflows and scripts | actionlint, shellcheck and offline zizmor. |
| Release | Offline version preparation and exact-commit APK/tooling artifact integrity tests. Every R8 build fails if it keeps the Adyen library's trust managers, `TerminalLocalAPIUnencrypted` or Apache HttpClient 5. |
| detekt | Type-resolved checks of main/test sources, including Compose, documentation and modern Kotlin/Jetpack API rules (reasons in `config/detekt/detekt.yml`); no baseline. |
| Dokka | All KDoc links resolve, including private code; generated HTML is in `<module>/build/dokka/html`. |
| Android Lint | All checks, including normally disabled checks and test sources; warnings fail. |
| Tests | JVM, Robolectric, Compose, ArchUnit and Android API-level tests; app tests run on Robolectric SDK 33 and again on the minimum SDK 28. |
| Kover | Line/branch bounds in each module's `build.gradle.kts`; the app's apply to non-UI debug code via `koverVerifyDebug`. |
| Manifest | Debug and release satisfy Adyen's constraints, including a PNG-only application icon and allowed permissions. |

Kotlin warnings are errors. Fix findings instead of adding suppressions, exclusions, ignores or baselines; existing
exceptions have reasons. [Code conventions](AGENTS.md#code-conventions) cover new architectural rules and detekt
coverage; don't add the overlapping plain `detekt` task. Aggregate app coverage has no bounds.

Build `./gradlew :app:assembleRelease` after dependency changes: R8 may need keep rules. CI runs the gate and release
build on pull requests. JVM modules aren't checked by Android Lint for API availability; `AndroidApiLevelTest` checks
reachable Java/Android APIs against minSdk 28 and accepts D8 backports. For example, use the charset-name overload of
`URLEncoder.encode`, not the API-33 `Charset` overload.

Ordinary CI and Release's direct CI job both use `scripts/ci`. It requires `CI=true`, runs the full
`qualityGate --continue`, stops on failure, then builds with `./gradlew :app:assembleRelease -PqualityGatePassed=true`
to skip the build's automatic fatal-only release lint pass. The flag requires `CI=true` and asserts that the gate
passed for this unchanged checkout; it does not record or verify a previous run. Explicit lint checks remain enabled, and
standalone release builds retain release lint by default. R8, signing and packaging checks are never skipped.

### Test setup

- `core` and `adyen`: plain JUnit. Crypto uses independent vectors; TLS tests use a fake Adyen root.
- `app`: Robolectric SDK 33, `TestApplication`, Compose v2 rules and `en-rAU` amounts. See
  [app test pitfalls](app/AGENTS.md#tests). Two worker JVMs run app tests; isolate filesystem fixtures across processes.
- Robolectric uses native graphics so captured PNGs contain rendered pixels, not legacy-renderer blank images.
- Compose tests use `createRecordingComposeRule`, wrapping the v2 rule. Failed test bodies capture unmerged trees,
  root PNGs and test/locale/dimension/timestamp metadata before Compose teardown under
  `app/build/reports/ui-failures/<test-task>/`. Passing tests do not capture; directories are unique across workers.
  Capture errors never replace the original assertion. These contain synthetic test data, not merchant screenshots;
  CI includes them in its existing reports artifact. Check timestamps: old failure artifacts may remain.
- `LocalizationTest` checks resource/format parity and writes receipt samples under
  `app/build/reports/localization/`. `LocalizedUiTest` checks Simplified/Traditional Chinese and Japanese checkout
  at AMS1 size. `ScreenTextTest` finds text cut off without an ellipsis in all seven languages at AMS1 size, and text
  still in English in the debug-only en-XA pseudo-locale.
- `testDebugUnitTestMinSdk` reruns every app test on Robolectric SDK 28 (S1F2's Android 9); it is part of `check`.
  Filter it like `testDebugUnitTest`.
- Financial actions are tapped twice in one input batch (`DoubleActivationTest`, `DoubleModificationTest`); add each
  new money-moving action there. `MalformedTerminalReplyTest` and `MalformedAdyenReplyTest` damage every field of
  real answers: a damaged answer may only become unknown, never change the outcome or be thrown.
- `InvariantsTest` and `SaleEventPropertiesTest` check properties on many seeded random inputs; a failure names the
  seed and case. Use whole code points when generating text.
- No real network or DNS in unit tests. Use the existing fake terminal, cloud, Payments app, Management and link APIs.
- Website helper integration tests use JDK HTTP/WebSocket APIs to drive Chromium's DevTools protocol. They load the
  actual localized pages from disk in a disposable profile with loopback-only debugging and outbound DNS blocked,
  exercise WebCrypto and async invalidation, and decode generated transfers with JVM implementations. No Node,
  browser-driver library or ChromeDriver is needed. Set `MINIMPOS_CHROME` to a Chrome/Chromium executable to override
  Google Chrome's standard macOS path or `google-chrome` on Linux. CI uses the browser already on its runner image.

### Known integration verification gaps

There is no real Adyen test account in CI. Still needing real-device/API verification: cloud offline/busy event
notifications; Payments app dual-install TEST/LIVE routing and separate boarding on physical phones;
Payments app return-URL encoding, error answers and size limits, boarding-credential `/me` permissions
and client-key propagation on physical phones; Management v3 Payments app role assignment, credential creation,
interrupted-creation listing/API-key recovery and propagation before boarding; TEST payment-link paid/PATCH answers
and line-item validation; whether a shopper reference without `recurringProcessingModel` stores no card;
the role name `GET /me` reports for `Merchant Recurring role`;
`GET /storedPaymentMethods` listing terminal-saved tokens, shopper-not-present (`ContAuth`) token payments for each
recurring processing model, and Checkout refunds (`/payments/{pspReference}/refunds`) of those payments;
Management single-store/merchant receipt details, read permissions and missing-store answers; terminal discovery,
reported network addresses and shared-key settings (including inherited settings, sensitive-field permissions,
targeted POIID search, full-object PATCH preservation and propagation of newly created keys to terminals); POS-wallet
configuration inheritance and routing for individually configured GCash, DANA, Kakao Pay and TrueMoney; native scanner
model reporting, single-scan/end behavior on local/network/cloud terminals, and wallet tokenization attempts. PayMe
merchant scanning is intentionally best-effort: Adyen's published wallet guide excludes it, and simulator success does
not establish support. Also needing real-terminal verification: scoped Management `terminalSettings` updates for
gratuities, DCC, MOTO and offline limits; `AskGratuity` with `SaleToPOIData` `TipOptions`/`AmountToTipOn`;
`GetTotalsRequest` and `ReconciliationRequest` (`SaleReconciliation`); chained `AdminRequest` waiting-screen sessions with
`DisplayRequest` and `InputRequest`; `AllowPartialAuthorisation` (`Result=Partial`); `AskGiving` two-step
`TransactionStatus` (`SaleID=AdyenGiving`) lookups; `MOTO` and `ForceEntryMode=Keyed` (MKE); and offline EMV /
Store-and-Forward `OnlineFlag`, `offlineAuthCode` and `unconfirmedBatchCount` fields.

For a merchant-scan TEST check, verify discovery for the actual store and currency, scan once with camera and native
hardware, cancel/switch/background a scan and verify late data cannot pay, exercise approval/decline/missing replies,
check token-created and no-token results, and refund the original confirmed payment. Confirm provider onboarding and
accepted code formats before LIVE use; do not execute real calls without separate explicit approval.
Do not describe simulator coverage as proof of these behaviors. Mobile SDK card readers are intentionally absent:
they require a backend for certificates, a private Maven repository, PCI MPoC and six-monthly updates.

## Make changes that fit

[AGENTS.md](AGENTS.md) is the single list of repository constraints for every contributor: money, compatibility,
security, payment boundaries, code conventions (KDoc, QR and `update.json` formats) and localization. This section
adds how to meet them.

- Follow existing module patterns. Use the Adyen library for every modeled API payload, crypto and certificate check;
  see [Adyen API model ownership](#adyen-api-model-ownership) and the [Adyen rules](adyen/AGENTS.md).
- Keep Compose components small. Pass state/callbacks rather than view models below screen level, reuse components,
  and use `ColumnScope`/`RowScope` extensions for siblings that need parent spacing.
- Put user text in resources. Group `strings.xml` by feature, keeping the same section and resource order in every
  language; place new entries beside related strings.
  Follow the [Adyen partner style guide](https://docs.adyen.com/development-resources/partner-style-guide) for app copy
  and merchant guides: short active sentences, consistent terms and numbered instructions. Name Adyen in documentation
  link text and link to pages, not fragments. Use reserved example domains and IP addresses. Keep exact UI labels,
  localized money formatting and explicit uncertainty in payment outcomes; do not apply prose rules to protocol values.
- From 1.0.0 onward, preserve upgrade paths from every released database schema. For each Room schema change, bump
  the database version, register a data-preserving migration, retain released exported schemas and export the new
  schema. Update `DatabaseSchemaTest` and add migration tests that verify schema validity and preservation of merchant
  data, including unfinished financial operations. Never add destructive fallback or reset local data silently.
- Settings, secrets, JSON columns and journal payloads have no Room schema export, and an unreadable settings or
  secrets file is preserved: storage health blocks access and startup work until the app can read it after restart.
  `StoredFormatTest` keeps their format in `app/src/test/resources/stored/formats.txt` and loads the files each
  release wrote from `stored/<version>/`. When the format changes, keep every released fixture loading, copy the
  test's `app/build/reports/stored-formats/formats.txt` over `stored/formats.txt`, and before releasing copy
  `app/build/reports/stored-formats/current/` to `stored/<version>/`.

### Translation terminology

Use `pt-BR`, `es-MX` and `fr-FR` consistently in app language choices, guide paths and screenshot fixtures.
Adyen’s localized [Portuguese help](https://help.adyen.com/pt_BR), [Spanish help](https://help.adyen.com/es_MX) and
[French help](https://help.adyen.com/fr_FR) guide the payment vocabulary. Keep product names, credential roles and
Customer Area navigation in English. Spanish uses Adyen’s localized portal name,
[Área de Clientes](https://help.adyen.com/es_MX/knowledge/account/access-your-customer-area/i-can-t-access-my-account-how-do-i-reset-my-mfa-method).
Distinguish a capture from settlement and terminal reconciliation:

| English | Brazilian Portuguese | Mexican Spanish | French |
| --- | --- | --- | --- |
| Payment | Pagamento | Pago | Paiement |
| Refund | Reembolso | Reembolso | Remboursement |
| Capture | Captura | Captura | Capture |
| Pre-authorization | Pré-autorização | Preautorización | Pré-autorisation |
| Tip | Gorjeta | Propina | Pourboire |
| Settlement | Liquidação | Liquidación | Règlement |
| Reconciliation | Reconciliação | Conciliación | Rapprochement |
| Receipt | Recibo | Recibo | Reçu |
| Shopper reference | Referência do comprador | Referencia del comprador | Référence acheteur |
| Manager PIN | PIN de gerente | PIN de gerente | PIN responsable |
| Admin PIN | PIN de administrador | PIN de administrador | PIN administrateur |

### Adyen API model ownership

All Adyen integrations live in `:adyen` and use the pinned Adyen Java library's request and response models:

| API | Model coverage |
| --- | --- |
| Checkout | Payment-method verification, captures, amount updates, refunds, payment links, stored-card listing and charges, plus errors. |
| Terminal | Payments, reversals, status, abort, diagnosis, printing, totals, reconciliation, admin, display and input; encrypted local and Payments app envelopes. |
| Cloud device | Connected-device listing and errors; forwarded Terminal API messages use the same Terminal models. |
| Management | Credential introspection, terminals, shared keys, capability settings, merchants, stores, payment methods and errors. |
| Payments app boarding | Boarding-token requests/responses and installation listings; revocation sends an empty JSON object. |

Checkout, Management, cloud listings and boarding use the library's Jackson serialization; Terminal messages use its
Gson builder. HTTP paths, query parameters, authentication, idempotency and timeouts belong to our transport adapters,
not SDK service clients, except the local Terminal service with our Android-compatible HTTP client installed.

Not every wire field has an SDK type. `AdminRequest.ServiceIdentification` (shopper sessions and barcode scans) and
`SaleData.SaleToPOIData` (tip options) contain Base64 JSON in SDK string fields. `AdditionalData`, `AdditionalResponse`,
metadata and string-valued tender options remain extensible. Payments app App Links and unstructured short errors are
also hand-mapped. Raw response reads are limited to these extensions, unmodeled amount-update `refusalReason` and
unknown-enum diagnostics; modeled saved-card refusal reasons use `PaymentResponse.refusalReason`.
Adyen documents the [admin-session format](https://docs.adyen.com/point-of-sale/shopper-engagement/create-session/).

`ArchitectureTest` rejects manual JSON trees in typed API adapters and checks HTTP ownership. Offline request/response,
malformed-answer and optimized-model tests cover serialization and uncertain outcomes. These tests do not establish
real-Adyen compatibility; the [verification gaps](#known-integration-verification-gaps) still apply.

### Shell scripts

Maintained shell scripts use `#!/usr/bin/env bash` so direct execution and `bash scripts/…` both select Bash from
`PATH`. Keep them compatible with macOS's bundled Bash 3.2 and Linux. Generated Gradle wrappers retain their upstream
POSIX `sh` and Windows syntax.

Use four-space indentation, `[[ … ]]` for Bash conditionals, arrays for command arguments and quoted expansions outside
assignments and conditionals. Prefer `printf` with a fixed format for messages. Resolve checkout paths with
`CDPATH='' cd -- …` so a caller's directory-search settings cannot affect them. Use `set -euo pipefail` unless the
script deliberately handles failures itself, as the Gradle and development runners do to preserve exit statuses.
The workflow shell blocks follow the same formatting and syntax conventions.

Format maintained scripts with `shfmt -w -i 4 -ci -sr scripts/*`. ShellCheck and actionlint are part of the full gate;
`tooling` tests cover argument quoting, failure propagation, signals and the docs/release helpers without real devices
or publication.

## Website and screenshots

The Pages workflow validates the website before publishing `docs/` unchanged. It runs on website, helper-contract,
website-test and relevant build changes, or manually; app-only and version-only commits do not redeploy the site.
The public site is <https://minimpos.app/>.

[Documentation changes](AGENTS.md#documentation-changes) lists the content and translation rules. Only the setup
helper has authored scripts (`docs/js/setup.js` and the vendored `qrcodegen.js`); its format changes alongside
`TransferCodec`, `QrChunks`, `TransferSeal` and the helper vectors in `SetupTransferTest`. Website tests protect
workflow coverage and translation parity, not a fixed count of pages or headings. For a quick check, run
`scripts/dev check website`; the full gate remains required.

The live host injects Cloudflare security, Rocket Loader and performance scripts. Every page's CSP permits local
scripts, Cloudflare's analytics script and Rocket Loader origin, and inline script elements for its dynamic security
bootstrap. Static meta policies cannot attach a per-response nonce to that bootstrap. Event-handler attributes,
string evaluation, frames and workers remain blocked. Fetch/beacon exceptions are restricted to the hosting paths in
[the security policy](SECURITY.md#transfers-and-the-setup-helper), not general application network access.
The local helper scripts opt out of Rocket Loader scheduling so the form appears promptly. Test policy behavior with
`SearchMetadataTest` and offline generation with `SetupHelperTest`; recheck hosted console/network behavior after deployment.
Cloudflare documents the [service CSP requirements](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/content-security-policies/)
and [dynamic security bootstrap](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/javascript-detections/).

Each language has a `site.webmanifest` with localized guide shortcuts and its home page as the launch URL. Keep the
shared application ID and site-wide scope when editing them. `docs/llms.txt` indexes the canonical pages in all seven
languages; update it when routes or guide names change. The guides' print stylesheet reveals manual instructions;
the setup helper excludes credentials and generated codes from printouts.

Use [the screenshot skill](.devin/skills/docs-screenshots/SKILL.md) to capture simulator demos and social images in all
seven supported languages, with its fictional local companies, prices and currencies. Each translated page uses its
own language's captures. Screenshots must match their original terminal frames: S1F2 540×960, AMS1 480×800.
Do not crop or stretch to fit,
label screenshots as demos, and keep demo data consistent. The guides' SMTP links must match
`SmtpProvider.helpUrl`; verify provider requirements when changing the list.

### Logo and Android icons

[`docs/favicon.svg`](docs/favicon.svg) is the logo source, with the app's primary green (`#00843F`) as its opaque
background. The website rounds its corners in CSS. The Android vector foreground preserves the artwork on a 108 dp
canvas, mapping the source's 512-unit square into the central 66 dp so every stroke stays inside the safe circle.
The same white foreground supplies the themed monochrome layer. Keep the application icon as density-specific PNGs
for Adyen's Customer Area, with 25% rounded corners; only the launcher activity uses the adaptive icon.
The website's `docs/icons/` PNGs also come from this SVG: 32 px favicon, 180 px Apple touch icon, and 192/512 px
manifest icons. The 512 px maskable icon scales the source to 384 px with 64 px green padding on every side, keeping
the artwork inside its safe circle.

[`app/src/main/ic_launcher-playstore.png`](app/src/main/ic_launcher-playstore.png) is the separate store asset:
512×512, 32-bit sRGB PNG, opaque square background, no corner mask or shadow, following
[Google Play's specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).
When changing the SVG, re-export this asset and the 48/72/96/144/192 px application PNGs, update the vector paths and
regenerate all seven social previews with `scripts/docs-assets social`. App screenshots need recapturing only if their
visible content changes.

### Repeatable screenshot refresh

Use a stopped, pre-seeded docs AVD containing `docs-<set>-<language>.tar` snapshots for all requested languages.
The snapshot set defaults to `1017` (`SNAPSHOT` overrides it). Create a fresh owned clone; existing destinations are
never overwritten. Run `start` in a separate terminal; it stays in the foreground. Its read-only emulator session
discards runtime edits on shutdown, leaving the source and saved fixture archives unchanged.

```sh
scripts/docs-emulator clone SOURCE_DOCS_AVD MiniMpos_Docs_Disposable_refresh
scripts/docs-emulator start MiniMpos_Docs_Disposable_refresh 5570
```

After approving demo resets on that clone (the driver waits for its ADB connection):

```sh
export DOCS_RESET=1 ANDROID_SERIAL=emulator-5570 SHOTS="$PWD/build/docs-raw"
scripts/docs-capture batch
scripts/docs-capture process
scripts/docs-assets social
scripts/docs-assets review
scripts/dev check website
scripts/docs-capture shutdown
```

`batch [LANG...]` builds/installs the current debug APK, applies each locale/time zone and fixed demo clock, restores
fixtures before each named flow and captures both terminal sizes. `DOCS_DATE` overrides the default fixture date
(`101619302026.00`, Android's `MMDDhhmmYYYY.ss`). Home captures refuse splash screens. Terminals have no system bars,
so the emulator display is taller than the terminal's by one status bar and one gesture bar (S1F2 720×1376, AMS1
480×872) and processing crops them: screenshots are full screen, like the terminal. Raw S1F2 and AMS1 images share one
folder; processing selects dimensions and bar heights from the terminal-specific filename.

`social` renders the checked-in template and seven-language copy with local assets only; use `MINIMPOS_CHROME` for a
Chrome/Chromium or `chrome-headless-shell` executable. `review` validates language parity, frame dimensions and indexed
palettes, then writes labeled contact sheets to `build/docs-assets` (`DOCS_WORK` overrides it). Inspect them: valid
dimensions do not prove the correct screen or transaction. Website checks also render all seven languages' five pages at
320/390/1280 px, reject broken images and horizontal overflow, and save 105 screenshots in
`website-test/build/reports/guide-layout`. Run `scripts/dev finish` before committing.

## Dependency and build maintenance

- Add dependencies via `gradle/libs.versions.toml`, pin versions at least seven days old, and check the release build.
  Never weaken security policies or minimum-release-age controls to make a build pass.
- Receipt address formatting uses bundled Google metadata, not runtime lookups. Its
  [attribution and refresh instructions](adyen/src/main/resources/google-address-formats.NOTICE.txt) pin the source;
  retain the accompanying license and run `StoreAddressTest` when refreshing it.
- For transitive advisories, pin a patched version in `settings.gradle.kts`'s `patched` table, check
  `./gradlew <module>:dependencies buildEnvironment`, then run the gate and signed release build. Remove overrides
  when upstream catches up. Dependency submission sends the resolved graph to GitHub.
- Dependabot leaves the Adyen Java library ungrouped so each upgrade is reviewed alone: check its model changes
  against [API model ownership](#adyen-api-model-ownership), then run the gate and release build.
- Adyen's library ships no R8 rules. Keep its model/serialization rules in `app/proguard-rules.pro`. The
  `TrustAllX509TrustManager` lint exception depends on stripping the TEST-only API; `verifyReleaseShrunkClasses`
  (run by every `assembleRelease`) fails when R8 keeps it, any trust manager in the library or Apache HttpClient 5.
- Mutation testing (`./gradlew mutationTest`, reports under `<module>/build/reports/pitest/`) shows tests that run
  code without noticing a change. It is too slow for the gate; add tests for meaningful survivors.
- rumdl, actionlint, shellcheck and zizmor use official archives with pinned hashes in `registerDownload`. Upgrade to a
  release at least seven days old; update all four platform checksums together, using release checksum files or
  verified archives (`gh attestation verify`). Dependabot cannot update these binaries.
- Pin workflow actions by SHA; checkout with `persist-credentials: false`; pass expressions into scripts via `env`.
  Keep caches out of the signing job and expose its push token only to the push step.
- For a dead-code audit, build release and inspect `app/build/outputs/mapping/release/usage.txt`. The block before the
  first `androidx.*` entry shows public members production never reaches; review serialization/Room members by hand.

Gradle 9.8 deprecates `Configuration.setVisible` (removal planned for Gradle 11). It is the build's only deprecation,
and the summary's "incompatible with Gradle 10" wording names the next major version for any deprecation. The latest
stable AGP, Dokka, Kover, KSP, detekt and gradle-pitest-plugin still call it; AGP 9.5 removes the calls. Upgrade the
plugins when fixed releases are at least seven days old. Do not suppress warnings or pin an older Gradle.
To see configuration-time warnings hidden by a reused configuration cache:

```sh
./gradlew qualityGate --no-configuration-cache --warning-mode all -Dorg.gradle.deprecation.trace=true
```

### GitHub workflow setup

CI retains every quality check and the R8 build. Release runs that same policy directly without a polling runner;
both use the tested `scripts/ci` script. Current actionlint and zizmor releases disagree on same-repository reusable
workflow syntax, so no reusable-workflow call or linter exception is needed.

- Set the repository Actions secret `GRADLE_ENCRYPTION_KEY` to a base64-encoded random 16-byte AES key. The pinned
  `setup-gradle` action needs it to persist encrypted configuration-cache entries. Do not print or commit the key.
  Ordinary build-cache reuse still works without it; forks receive no secrets. Gradle caches remain read-only outside
  main, and the signing job never restores a cache.
- After deploying `codeql.yml`, switch CodeQL from default setup to advanced setup. Keep the default query suite,
  `actions` and `java-kotlin` analyses, PR/main scans and the weekly scan. The manual Kotlin build compiles every
  production module without APK packaging, D8 or R8. It disables compilation and configuration-cache reuse and
  forces compiler execution for extraction. Check the first analysis's source coverage before retiring default setup.
- After deploying `pages.yml`, set Settings › Pages › Build and deployment › Source to **GitHub Actions**, then run
  Pages manually for the first verified deployment. Keep the `github-pages` environment restricted to main.
- For the custom domain, verify ownership in the GitHub account settings, then set **Settings › Pages › Custom domain**
  to `minimpos.app` before configuring DNS. Follow
  [GitHub's custom-domain instructions](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site)
  for the apex records and optional `www` redirect, then enable **Enforce HTTPS**. The Actions-based deployment selects
  the domain from repository settings, not a `CNAME` file.
- Dependency submission runs when Gradle scripts, properties, wrapper/catalog or its workflow change, weekly, and
  manually. After a successful explicit submission, verify its resolved transitive snapshot, including the security
  floors in `settings.gradle.kts`, then disable Settings › Advanced Security › Automatic dependency submission.
  Keep the dependency graph, Dependabot alerts and security updates enabled. Do not disable automatic submission
  before confirming the explicit workflow's graph.

Repository settings are not part of a Git commit. Deploy the workflows before changing these settings; disabling the
existing scanners or publication before their replacements exist creates a verification or deployment gap.

## Signing your own APK

Prefer the maintainer-signed APK for merchant installations. If you maintain a fork, create a signing key once and
keep it safe, outside the repository:

```sh
keytool -genkeypair -keystore ~/.android/minimpos-release.jks -alias minimpos -keyalg RSA -keysize 4096 \
  -validity 10000 -dname "CN=Mini mPOS"
```

Set `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in an untracked `keystore.properties`, then build:

```sh
./gradlew :app:assembleRelease
```

The signed output is `app/build/outputs/apk/release/app-release.apk`. Without signing properties the build produces
an unsigned APK, which Adyen accepts as an upload but terminals cannot install. The application ID is
`app.minimpos`; each ID is tied to the key used for its first upload. Keep the same key and use a higher
version code for each deployment. A signing-key mismatch requires Adyen Support to remove earlier uploads.

Forks must maintain their own release process, credentials and signing history.

## Release the app

`version.properties` is managed by **Actions › Release › Run workflow** on `main`. Choose patch/minor/major and supply
the release notes following [the style below](#release-note-style);
the workflow commits the new version first. It reuses a successful main push/manual CI run for that exact commit
only when its retained artifact passes current commit, version, run-attempt and SHA-256 verification. Missing or
expired artifacts require fresh CI; changed or invalid artifacts block release. Pending, failed, cancelled and PR
runs are never reused. Otherwise, a direct CI job runs the full quality gate and R8
build, then saves the unsigned APK and tested executable tooling JAR with their SHA-256 hashes, commit, version and
run attempt. There is no dispatched duplicate CI or runner waiting for another workflow.
Failed or cancelled verification blocks publication.

Only successful CI allows the release job to access the signing environment. It runs CI's tooling JAR with Java,
without rebuilding tooling, Gradle plugins or dependency resolution. It verifies the artifact's provenance,
checksum, application ID and version, signs CI's APK without rebuilding it, checks its signature and alignment, tags
the verified commit as `vX.Y.Z`, and publishes the APK and notes. The full quality gate runs once, in the direct CI
job or reused successful CI; no Gradle
plugins execute with signing secrets. Main must still point to the verified commit before tagging.

A failed attempt can leave an unpublished version commit on main. Choose **resume** to reuse valid exact-commit CI
or run fresh CI and publish it without another bump; the version must come from a version-only `Release X.Y.Z`
commit without an existing tag.
Later fixes can follow that commit before resuming. If tagging
succeeded but publication failed, finish publication for that existing tag rather than bumping again. CI artifacts
expire after 14 days; resume produces a fresh one. Only the latest release is maintained.

The repository's `release` environment is restricted to `main`. Its secrets are `RELEASE_KEYSTORE` (base64 keystore)
and `RELEASE_SIGNING_PROPERTIES` (`storePassword`, `keyAlias`, `keyPassword`). Never commit them or local SDK/signing
files. See [SECURITY.md](SECURITY.md) for private vulnerability reporting.

After dispatching the Release workflow with the approved version bump, use the checked-in monitor/verifier:

```sh
scripts/release-check watch RUN_ID vX.Y.Z vPREVIOUS.VERSION.NUMBER
scripts/release-check verify vX.Y.Z vPREVIOUS.VERSION.NUMBER
```

Use `watch` while the workflow runs, or `verify` for an already published release; do not run both routinely.
Both require approved GitHub access and the authenticated `gh` CLI. They only read GitHub and download artifacts;
they never commit, push, dispatch, tag or publish. The optional previous tag enables signing-certificate continuity
checks. SDK `apksigner`, `zipalign` and `aapt2` verify the released APK's signature at API 28, 16 KB alignment,
application ID, version and minimum SDK. `MINIMPOS_BUILD_TOOLS` overrides the detected SDK build-tools directory.
Kotlin tooling checks the two GitHub SHA-256 asset digests and exact `update.json` contract against the release tag's
version file. Verification files stay in a fresh `build/release-check.*` directory; the checkout and refs stay unchanged.

### Release-note style

Write GitHub release notes in US English for merchants deciding whether to update. Use a short bullet list, normally
one to five bullets; larger feature releases may need more. Lead with new capabilities and significant fixes, grouping
related changes. Describe the visible behavior and useful conditions or limitations, rather than copying commit titles.
Small releases need only one specific bullet; do not pad them with generic claims of improvements or reliability.

For example:

- Added French, Mexican Spanish, and Brazilian Portuguese across the app and merchant guides.
- Receipt business imports now let you fill missing details or replace saved details. Blank imported fields preserve
  saved values.
- Fixed a crash when using Charge token in release builds.

Compare the previous published tag with the exact release commit, including direct commits as well as merged PRs.
Check the relevant diffs when a commit title is vague; describe only behavior included in that release. Keep historically
accurate capabilities and limitations. Mention required merchant action or compatibility changes when applicable, and
never imply that simulator tests establish real-Adyen support.

Omit routine dependency bumps, refactoring, test/CI results, signing details, contributor lists and internal identifiers.
If a release contains only internal maintenance, use one specific plain-English maintenance bullet without inventing
merchant benefits. Do not use GitHub's generated notes as the final body. The workflow requires nonblank authored notes
before changing the version and appends a **Full Changelog** comparison link automatically. With the CLI, pass a UTF-8
Markdown file using `gh workflow run release.yml -f bump=patch -F notes=@/absolute/path/release-notes.md`.
Supply notes again when resuming; review the full range from the previous published release, including any later fixes.
After publication, read back the GitHub release body along with the usual artifact verification.
