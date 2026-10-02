# Mini mPOS

Android POS app for Adyen Android payment terminals. On a terminal it takes payments through the local Terminal API on
the same device (`https://localhost:8443/nexo`); on a tablet or phone through a terminal on the network, Adyen's Cloud
device API or the Adyen Payments app (Tap to Pay); anywhere through its built-in simulator.

- This file holds what applies to the whole repository and what the code does not tell you. `app/AGENTS.md` and
  `terminal-api/AGENTS.md` hold each module's architecture rules and test setup: read them before changing or
  planning changes to that module.
- `CONTEXT.md` is the domain glossary: use its terms (and code names) for new modules, tests and docs, and add a term
  there before naming a module after a new concept.
- KDoc in the code is the detailed reference. `CONTRIBUTING.md` is the contributor guide; `README.md` and `docs/` are
  for users (see [Documentation](#documentation)).

## Modules

- `:core` – pure Kotlin: money/tax, cart, refund apportioning, receipt document model and renderers, QR formats
  (`TransferCodec` `MPC1:`, refund `MPR1*`), Adyen currency table (Adyen's decimals win over ISO), `PaymentMethods`.
  There is deliberately no country/region setting (blank currency follows the device's country, else EUR; a new
  installation's tax rates and price style too, `StarterTax`, whose table of standard rates needs keeping up to date).
- `:terminal-api` – the Terminal API client on top of `com.adyen:adyen-java-api-library`: local, cloud and Payments app
  transports, Checkout API calls (captures, payment links), the in-process simulator. Knows neither Android nor
  `:core`.
- `:app` – the Android app (Compose, Room, DataStore, manual DI in `AppContainer`).
- `:website-test` – no app code: checks the `docs/` website, whose build files live here because GitHub Pages publishes
  `docs/` as is.

## Build and verify

- Run `./gradlew qualityGate` after every change: Spotless/ktlint, detekt (no baseline), Dokka with `failOnWarning`
  (every `[link]` in KDoc must resolve), Android Lint (warnings are errors), unit/Robolectric/Compose tests, ArchUnit
  `ArchitectureTest` in each module, `AndroidApiLevelTest` (in `:app`), Kover thresholds (core 95/85 and terminal-api
  90/75 line/branch %, app non-UI 80 % lines), and `verify{Debug,Release}TerminalManifest`. Kotlin warnings are errors;
  the build output stays warning-free.
- The gate also checks the non-Kotlin files with nothing but the JDK: `markdownCheck` (rumdl, `.rumdl.toml`:
  markdownlint's rules, 120-column lines outside tables/code, relative links must exist; line breaks are kept, so wrap
  prose by hand), `actionlint` (shellcheck on `run:` scripts) and `zizmor` (offline), all `ToolCheck`s in the root
  `build.gradle.kts`; and `:website-test:check` (`WebsiteTest` with jsoup, and `htmlCheck`: the W3C Nu Html Checker on
  the HTML and CSS, failing on any message, informational ones included). Spotless `misc` keeps whitespace and final
  newlines in the other text files (YAML included; there is no YAML formatter).
- rumdl, actionlint, shellcheck and zizmor are official release binaries that `ToolDownload` fetches and checks against
  the SHA-256 pinned in `registerDownload` (macOS and Linux, x64 and arm64). Dependabot cannot update them: to upgrade,
  pick a release at least 7 days old, take the checksums from its checksum files (or hash the archives after
  `gh attestation verify`), and update the version and all four checksums together.
- A new architectural decision gets its ArchUnit rule in the same change (a starred bullet in the module's
  `AGENTS.md`), with a comment saying why; check a new rule fails on a deliberate violation before relying on it.
- Format with `./gradlew spotlessApply` (also runs `rumdl fmt`). After editing `.editorconfig`, run `./gradlew --stop`
  (ktlint caches it).
- Fix findings instead of silencing them: no new `@Suppress`, lint ignores, baselines or rule exclusions. The existing
  ones are deliberate and commented.
- Versions live in `version.properties`; never bump them by hand. Releases come only from the Release workflow
  (`.github/workflows/release.yml`), which raises the version, signs, tags and publishes.
- Commits use Adam Stiskala <github@adamstiskala.com> (not the Adyen work email) and carry no bot attribution.
- Release APK: `./gradlew :app:assembleRelease`, signed only when `keystore.properties` exists (never commit it; the
  key is `~/.android/minimpos-release.jks`, alias `minimpos`, outside the repo). Build it after adding a dependency:
  R8 may need keep rules.
- Upgrading the Adyen library (Dependabot keeps it ungrouped): it ships no R8 rules, so `app/proguard-rules.pro` keeps
  its models and serialisation classes. Check the release dex afterwards (the lint ignore for `TrustAllX509TrustManager`
  relies on R8 stripping the TEST-only API).
- Security advisories on transitive dependencies (Dependabot cannot fix them): add the patched version, at least 7
  days old, to the `patched` table in `settings.gradle.kts`; check `./gradlew <module>:dependencies buildEnvironment`
  and run the gate plus the signed release build. Drop entries once upstream catches up. The Dependency submission
  workflow sends the resolved graph to GitHub, so Dependabot alerts name them.
- Workflows: pin actions by commit SHA, check out with `persist-credentials: false` (the release passes its token only
  to the push), pass `${{ }}` values to `run:` scripts through `env`, and keep caches out of the release job.
- Dead-code audit: after `:app:assembleRelease`, the first block of `app/build/outputs/mapping/release/usage.txt`
  (before any `androidx.*` entry) lists public members production never reaches. Check `@Serializable`/Room members by
  hand (kept by rules).

## Adyen terminal constraints

- `VerifyTerminalManifestTask` enforces: minSdk 28, no CATEGORY_HOME, no `testOnly`, only allowlisted permissions
  (INTERNET, ACCESS_NETWORK_STATE, CAMERA; `StripManifestPermissionsTask` removes androidx's
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), and a PNG-only `android:icon` (the Customer Area cannot render adaptive
  icons; never add an adaptive `ic_launcher`; `MainActivity` has its own `ic_launcher_adaptive`).
- `applicationId` is `io.github.astiskala.minimpos`; never change it or reuse the abandoned `io.minimpos.app` (the Kotlin
  package/namespace). Each package is tied to the key of its first upload: always sign with the same key, and upload a
  new `versionCode` each time. Unsigned APKs upload fine but fail to install on terminals.
- Smallest target screen: AMS1, 4" 480×800 hdpi, ~320×460 dp usable, Android 10, no printer.
- Terminals have no Google Play services: no dependency may need them (hence CameraX + ZXing for scanning).
- On a terminal the POIID comes from `Settings.Global.DEVICE_NAME` and the host is `localhost`; only the shared key (and
  the Checkout API) is entered. The manifest's `<queries>` (the two Payments app packages), the
  `minimpos://paymentsapp` VIEW filter on the `singleTask` `MainActivity` and the unexported `FileProvider` for shared
  receipt images add no permission, so the same APK stays acceptable for terminals.
- `:core` and `:terminal-api` are JVM modules, so Android Lint does not check their API levels: `AndroidApiLevelTest`
  checks every Java/Android class, method and field they reach against the compile SDK's `api-versions.xml` at the
  app's minSdk, accepting what D8 backports (`listBackportedMethods`). E.g. `URLEncoder.encode(String, Charset)` is
  API 33; use the charset name.
- There is no adb on terminals: test on an emulator with "Payments go to" = Simulator (the default off a
  terminal), or a terminal on the LAN. Use the `docs-screenshots` skill (`.devin/skills/docs-screenshots/SKILL.md`)
  for the AMS1 emulator and the website screenshots.

## Where payments go

- "Payments go to" (`TerminalMode`): this terminal or one on the network (local Terminal API, shared key), `CLOUD`
  (Cloud device API `/sync` with the `CHECKOUT_API_KEY` secret, which also does captures; payment timeout at least
  160 s), `PAYMENTS_APP` (Tap to Pay through the Adyen Payments app, shared key; POIID is the boarded installation ID),
  or the simulator. Cloud and Payments app are only offered off-terminal; Automatic stays the simulator there. The
  Payments app needs the shared key too (Adyen's docs: requests are encrypted as for local communications).
- The Checkout API is required wherever payments go but the simulator (a product decision: captures, adjustments and
  payment links all need it): payments wait for it (`TerminalSetup.problem`, the Home setup card) except for a not yet
  detected environment, which the first connection finds; connection checks, refunds and printing need only the
  destination (`TerminalSetup.connectionProblem`). There is no Customer Area capture mode any more; `CaptureStatus.MANUAL`
  stays only for sales stored by older versions.
- The environment (TEST/LIVE) is never a setting: it comes from the terminal certificate, else the endpoint that accepts
  the cloud API key (`CloudDevices.detect`: TEST, then the device country's live data centre, then the others), else the
  installed Payments app package (both installed is a setup problem), and picks the Checkout API endpoint. Changing the
  mode clears the stored environment.
- The Payments app takes only payments and reversals: no print, abort or diagnosis (the connection check only checks the
  setup), no printer; status checks are answered only from answers that arrived with no exchange waiting
  (`AppLinkExchange.lateReplies`). `PaymentsAppBridge` is the activity's side; coming back without an answer makes the
  outcome unknown. The Payments app API key (`PAYMENTS_APP_API_KEY`) boards and revokes it (`TapToPaySetup`).
- Every sale is written as PENDING before the terminal is called; interrupted ones become UNKNOWN at startup. Without
  a response after the timeout (default 120 s) the client polls the status every 5 s while it is `InProgress`.
- Payment links (`PaymentLinks`) skip the destination: the Checkout API's `/paymentLinks`, offered when Settings ›
  Payments › Offer payment links is on and `ApiSetup.Complete` (`TerminalSetup.paymentLinks`; never simulated). Without
  webhooks the outcome comes from `GET /paymentLinks/{id}` (every 10 s while the link screen is open, and on request)
  and carries no PSP reference, so paid links are refunded in the Customer Area. The request is made from the stored
  sale with the key `link-{saleId}`, so an unknown creation is sent again identically.
- The terminal's own receipt printing is suppressed (`tenderOption=ReceiptHandler`): the app prints one combined slip
  (header, items, tax, Adyen's card receipt lines, footer), with the refund QR code as a second print request.

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
- QR formats (`TransferCodec`, refund QR) are read by terminals on older versions: a format change bumps its version
  and keeps decoding the older ones.

## Product decisions (deliberate; do not "fix")

- API keys on tablets and phones (cloud, Tap to Pay) go against Adyen's advice to keep keys on a server; the app has no
  backend by design, so the docs and SECURITY.md say so and recommend a terminal on the network. The Mobile SDK (card
  readers) is deliberately not integrated: it needs a backend for `/auth/certificate`, a private Maven repo, PCI MPoC and
  six-monthly updates.
- Not verified against Adyen yet (no test account in CI): the cloud's event notifications for an offline or busy
  terminal, the Payments app's return URL encoding, `error` answers and size limits on a real phone, payment links'
  answers on TEST (the `paid` status, the PATCH answer for an already paid link, line item validation), and that a
  terminal payment with a `shopperReference` but no `recurringProcessingModel` stores no card.
- No TEST banner (test terminals show TEST themselves); `ModeBanner` only for the simulator. No "settings not
  protected" warning and no terminal/printer status line on Home (they are in Settings › About). Home is, top to
  bottom: New sale and New pre-authorization as full-width green tiles (both always, since a pre-authorisation can be
  a custom amount), Refund and History as half-width tiles, then Products/Settings as slim grey buttons. Secret field
  placeholders stay one line ("Type to replace").
- Every product has a tax rate (0% rates for untaxed items; no "tax applies" switch); "Charge tax" off taxes nothing
  but keeps rates. The last tax rate cannot be deleted.
- The customer reference is asked for exactly when it is the shopper reference (no separate switch); with the email
  as shopper reference, email capture always includes "before payment". The shopper reference goes with every payment
  (terminal and link); "Offer to save cards" (`offerCardSaving`, shown only with a shopper reference) decides whether
  checkout offers Save card. New installations have no shopper reference (`ShopperReferenceSource.NONE`), ask for no
  transaction reference and print automatically, so checkout is only Pay.
- Settings › Email's provider buttons (`SmtpProvider`) list only providers that still accept a password for SMTP,
  since `SmtpMailer` has no OAuth: Outlook.com is left out, and Microsoft 365's advice follows Microsoft's SMTP AUTH
  retirement (off by default from the end of 2026). Check the table against the providers' help pages when it changes.
  The app links the help pages only off a terminal (terminals have no browser); the getting started guide lists the
  same links, so keep `SmtpProvider.helpUrl` and the guide's three languages in sync.
- Home's setup card shows wherever payments go but the simulator, saying what is missing (on a terminal whose shared
  key is missing, the shared key). A successful connection test names the currency while it follows the device's
  region. Settings › Terminal is numbered steps per destination (`TerminalSteps`): this terminal: shared key, Checkout
  API; a terminal on the network: address and POIID, shared key, Checkout API; the cloud: Adyen account (merchant
  account and the API key it shares), terminal with one test of both (`SettingsTest.CLOUD`); Tap to Pay: Payments app
  (Google Play buttons for both apps while none is installed; the app re-reads the device on resume), Checkout API,
  setting up Tap to Pay, shared key. The live URL prefix is asked for only once the environment is LIVE.
- Buttons: Settings groups their buttons in `SettingActions` (spaced like `BottomActions`), every Settings button has an
  icon, and removing something saved (keys, PIN, phone, history) is a red `ConfirmedRemoval` that asks first.
- Pre-authorisations and tips follow `CONTEXT.md` (cancelled, not refunded; "Held" in day totals; a tip over 20% is
  adjusted first, else overcaptured). Tip on the receipt is only offered with a printer; a refused adjustment leaves
  the tip unsaved. Idempotency keys (`capture-{saleId}-{amount}`, `adjust-{saleId}-{heldBefore}-{amount}`) make
  retries safe.
- Payment links are for sales only (no pre-authorisations, no tip on the receipt) and send line items, the shopper's
  email and reference, card saving (`askForConsent`), the device locale and country. An unpaid link counts nowhere in
  day totals and needs no merchant copy; its receipt is the unpaid receipt. Sharing (receipts as PNG; links as text
  with the unpaid receipt) is offered only off-terminal (`TerminalState.canShare`); terminals email instead.
- History search: every word must match (case-insensitive) a reference, auth code, shopper data, card last 4, brand or
  wallet, or be exactly the amount; combined with the filter chips, and day totals cover only what is shown.
- Setting up another device: one QR transfer with switches for catalogue, settings (minus the device fields of
  `AppSettings.withDeviceFieldsOf`, which each section names next to its fields) and secrets, sealed by `TransferSeal`
  with a 12-character code. What travels is `AppSettings.shared()` (taken over with `takingOver`), so a new settings
  section or field transfers by default; the import rules (currency after import, transfer code, skipped secrets) are
  `SetupTransfer`'s and `ReceivedTransfer`'s.
- The setup helper (`docs/setup.html`, `docs/js/setup.js`) makes transfer codes in the browser: a connection
  (`ConnectionSetup`, `TransferCodec` version 5, written only when there is one) and sealed secrets, imported through
  "Set up from another device". It sets only what it holds, device fields included, never the other settings; on a
  terminal only "this terminal" changes where payments go. `setup.js` mirrors `TransferCodec`, `QrChunks` and
  `TransferSeal` (stored DEFLATE blocks, WebCrypto PBKDF2/AES-GCM): change them together, and regenerate the codes in
  `SetupTransferTest`'s setup helper test from the page. The page sends nothing (CSP `connect-src 'none'`); the QR
  encoder is a vendored, compiled Project Nayuki release (`docs/js/qrcodegen.js`, header says which).
- Terminology: the **device** runs Mini mPOS (a terminal, tablet or phone); the **terminal** takes the card. User
  text says "device" for what runs the app (transfers, data, PIN) and keeps "terminal" for payments and for Settings ›
  Terminal; the docs keep saying that Mini mPOS runs on the terminal itself, which sets it apart.

## Documentation

- Each reader has one file: `README.md` (what the app does, how to install and run it), the `docs/` website (merchants;
  `https://astiskala.github.io/minimpos/`, published by GitHub Pages as is), `CONTRIBUTING.md` (contributors: setup,
  what the gate runs, guidelines), `SECURITY.md` (reporting, data protection), `CONTEXT.md` and the `AGENTS.md` files.
  Say a thing once, where its reader looks, and link to it from elsewhere.
- Keep feature claims, Customer Area paths and Settings names in README, `docs/index.html`,
  `docs/getting-started.html` and `docs/setup.html` in sync with the app, and change the site's three languages
  together (`docs/zh-CN/`, `docs/ja/`: reciprocal language switches, canonical/hreflang links, a script-free `<details>`
  globe menu at the top right of the header). `./gradlew :website-test:check` checks all nine pages (void elements take
  no trailing slash); a new guide section also goes into its `GUIDE_SECTIONS`, the guides' `<code>` elements must match
  in order, and the setup helpers' form fields, links and messages must match. Only the setup helper runs scripts.
- The app is pre-release: docs and UI carry no warnings about older app versions (reading codes from them, keeping
  devices on the same version, data they left behind).
- The guides cover what Mini mPOS needs and link to Adyen's documentation for how Adyen works (account, Customer Area,
  roles, endpoints), rather than describing Adyen's screens, which change and are Adyen's to support.
- Screenshots are English demo screens on the simulator, visibly disclosed, shown in original AMS1/S1F2-style SVG
  frames (bottom bezels blank, no NFC symbol on the AMS1's top); each matches its frame's screen (S1F2 9:16 at 540×960,
  AMS1 3:5 at 480×800). Re-capture them, and `social.png`, with the `docs-screenshots` skill.
- The guides explain receipt-language settings, the physical-printer check and the limits: not Chinese tax invoices or
  guaranteed Japanese qualified invoices.
