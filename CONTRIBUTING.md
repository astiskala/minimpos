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
a terminal on your network for integration testing. Follow the merchant [Quick start guide](docs/quick-start.html).

Android Studio is the simplest editor. The repository's VS Code settings disable the Java extension's Gradle import;
there is no Java code, and that importer cannot handle this build.

## Find the right code

| Module | Responsibility |
| --- | --- |
| `core` | Android-free money/tax, cart, refunds, receipt model/renderers, QR formats, currencies and payment methods. |
| `adyen` | Android-free Terminal, Checkout, Cloud device and Management API integration, transports and simulator. |
| `app` | Compose UI, Room, DataStore, Keystore, email, camera scanning and manual DI in `AppContainer`. |
| `website-test` | Tests and validation for the static website in `docs/`; no app code. |
| `tooling` | Kotlin/JVM release CLI, signing-property extraction and shell-runner regression tests; no app dependencies. |

[CONTEXT.md](CONTEXT.md) defines domain terms and decision owners. Read the relevant
[module rules](app/AGENTS.md) ([Adyen module](adyen/AGENTS.md)) before changing a module. Their starred architectural
rules are enforced by ArchUnit. Detailed contracts belong in KDoc, not a parallel architecture manual.

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

It overrides console mode to plain and removes only known task/cache progress. Warnings,
unknown output, failure diagnostics and the exit status survive. Every run saves complete stdout/stderr in a separate
private log under ignored `build/gradle-logs/`; the runner prints its path. It does not use `--quiet`, disable checks or
truncate failures. Signals are forwarded to Gradle and return a nonzero status. Use regular `./gradlew` for interactive
progress or detailed logging (`--info`, `--stacktrace`). The runner uses Bash and standard system tools, without a
bootstrap build, an installed Kotlin compiler or Node. Failed runs also save a private `repair.txt` beside the log:
exit status, shell-quoted rerun, failed tasks, assertions and source frames extracted from that run. This is navigation,
not a replacement for the full diagnostics.

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
| `check AREA` | Module `check` for `core`/`adyen`/`app`; focused `website`, `workflows`, `markdown`, `architecture`, `localization`, `ui`, `qr`, `db` or `payment` lanes. |
| `finish` | Format, then full `qualityGate --continue`; formatting failure stops the gate, gate failure stays nonzero. |
| `preflight` | Read-only check of JDK 17+, SDK 37/build tools and Chrome; reports missing cached linters without failing for them. |
| `warm` | Preflight, then compiler/test/browser/pinned-tool warmup; does not install SDKs or change config. |
| `report` | Build current tooling, then summarize existing JUnit XML: exact assertions, first project frame, quoted reruns and slowest suites. |
| `profile WORKERS [PATTERN]` | Rerun only the app test task with 1–4 workers and Gradle `--profile`; default pattern `*ui.*`. |

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
| Release | Offline version preparation and exact-commit APK/tooling artifact integrity tests. |
| detekt | Type-resolved checks of main/test sources, including Compose and documentation rules; no baseline. |
| Dokka | All KDoc links resolve, including private code; generated HTML is in `<module>/build/dokka/html`. |
| Android Lint | All checks, including normally disabled checks and test sources; warnings fail. |
| Tests | JVM, Robolectric, Compose, ArchUnit and Android API-level tests. |
| Kover | Core 95/85%, Adyen 90/75% line/branch coverage; app non-UI 80% lines via `koverVerifyDebug`. |
| Manifest | Debug and release satisfy Adyen's constraints, including a PNG-only application icon and allowed permissions. |

Kotlin warnings are errors. Fix findings instead of adding suppressions, exclusions, ignores or baselines. Existing
exceptions have reasons. A new architectural decision needs a rule, a reason comment, and a check that a deliberate
violation fails. Keep new source sets covered by type-resolved detekt; don't add the overlapping plain `detekt` task.
Aggregate app coverage has no bounds and must not pull release compilation into the gate.

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
  `app/build/reports/localization/`. `LocalizedUiTest` checks Chinese/Japanese checkout at AMS1 size.
- No real network or DNS in unit tests. Use the existing fake terminal, cloud, Payments app, Management and link APIs.
- Website helper integration tests use JDK HTTP/WebSocket APIs to drive Chromium's DevTools protocol. They load the
  actual localized pages from disk in a disposable profile with loopback-only debugging and outbound DNS blocked,
  exercise WebCrypto and async invalidation, and decode generated transfers with JVM implementations. No Node,
  browser-driver library or ChromeDriver is needed. Set `MINIMPOS_CHROME` to a Chrome/Chromium executable to override
  Google Chrome's standard macOS path or `google-chrome` on Linux. CI uses the browser already on its runner image.

### Known integration verification gaps

There is no real Adyen test account in CI. Still needing real-device/API verification: cloud offline/busy event
notifications; Payments app return-URL encoding, error answers and size limits; TEST payment-link paid/PATCH answers
and line-item validation; whether a shopper reference without `recurringProcessingModel` stores no card;
Management single-store/merchant receipt details, read permissions and missing-store answers; terminal discovery,
reported network addresses and shared-key settings (including inherited settings, sensitive-field permissions,
targeted POIID search, full-object PATCH preservation and propagation of newly created keys to terminals); POS-wallet
configuration inheritance and routing for individually configured GCash, DANA, Kakao Pay and TrueMoney; native scanner
model reporting, single-scan/end behavior on local/network/cloud terminals, and wallet tokenization attempts. PayMe
merchant scanning is intentionally best-effort: Adyen's published wallet guide excludes it, and simulator success does
not establish support.

For a merchant-scan TEST check, verify discovery for the actual store and currency, scan once with camera and native
hardware, cancel/switch/background a scan and verify late data cannot pay, exercise approval/decline/missing replies,
check token-created and no-token results, and refund the original confirmed payment. Confirm provider onboarding and
accepted code formats before LIVE use; do not execute real calls without separate explicit approval.
Do not describe simulator coverage as proof of these behaviors. Mobile SDK card readers are intentionally absent:
they require a backend for certificates, a private Maven repository, PCI MPoC and six-monthly updates.

## Make changes that fit

- Follow [repository constraints](AGENTS.md) and existing module patterns. Use `Long` minor units and Adyen's decimals,
  never floating-point money; tax rates are thousandths of a percent.
- Use the Adyen library for Terminal API models, crypto and certificate checks. The Android adaptations and exceptions
  for plain JSON Checkout/cloud calls are in [the Adyen rules](adyen/AGENTS.md).
- Every public/protected declaration needs KDoc (tests exempt): useful contracts, units, null meaning, threading,
  formats and errors. Constructor tags, when used, cover every parameter in order: public properties use `@property`,
  others `@param`. Don't merely repeat a declaration's name.
- Keep Compose components small. Pass state/callbacks rather than view models below screen level, reuse components,
  and use `ColumnScope`/`RowScope` extensions for siblings that need parent spacing.
- Put user text in resources, in short US English, with Chinese/Japanese translations and matching format arguments.
  CJK plurals use `other`. Preserve identifiers, stored spelling and Adyen text; keep Customer Area paths English.
  Follow the [Adyen partner style guide](https://docs.adyen.com/development-resources/partner-style-guide) for app copy
  and merchant guides: short active sentences, consistent terms and numbered instructions. Name Adyen in documentation
  link text and link to pages, not fragments. Use reserved example domains and IP addresses. Keep exact UI labels,
  localized money formatting and explicit uncertainty in payment outcomes; do not apply prose rules to protocol values.
- Pre-launch schemas and QR formats have one current contract. Update models, exported Room schema, tests and setup
  helper together, without earlier-build compatibility. Never add destructive fallback or reset local data silently.
- New-install localized/regional defaults must not overwrite stored text or imported settings.

## Website and screenshots

The Pages workflow validates the website before publishing `docs/` unchanged. It runs on website, helper-contract,
website-test and relevant build changes, or manually; app-only and version-only commits do not redeploy the site.
Keep the existing visual design and script-free guides; only the setup
helper runs `docs/js/setup.js` and the vendored `qrcodegen.js`. Preserve the helper's no-network policy and update its
format alongside `TransferCodec`, `QrChunks`, `TransferSeal` and the helper vectors in `SetupTransferTest`.

The merchant documentation has three guides: setup, using the app, troubleshooting. Each fact has one home, with
links from elsewhere. Update English, Chinese and Japanese together, including metadata and reciprocal language
switches. Website tests protect workflow coverage and translation parity, not a fixed count of pages or headings.
For a quick check, run `./gradlew :website-test:check`; the full gate remains required.

Use [the screenshot skill](.devin/skills/docs-screenshots/SKILL.md) to capture English simulator demos and the social
image. Screenshots must match their original terminal frames: S1F2 540×960, AMS1 480×800. Do not crop or stretch to fit,
label screenshots as demos, and keep demo data consistent. The guides' SMTP links must match
`SmtpProvider.helpUrl`; verify provider requirements when changing the list.

## Dependency and build maintenance

- Add dependencies via `gradle/libs.versions.toml`, pin versions at least seven days old, and check the release build.
  Never weaken security policies or minimum-release-age controls to make a build pass.
- For transitive advisories, pin a patched version in `settings.gradle.kts`'s `patched` table, check
  `./gradlew <module>:dependencies buildEnvironment`, then run the gate and signed release build. Remove overrides
  when upstream catches up. Dependency submission sends the resolved graph to GitHub.
- Adyen's library ships no R8 rules. Keep its model/serialization rules in `app/proguard-rules.pro` and inspect release
  dex after upgrades: the `TrustAllX509TrustManager` lint exception depends on stripping the TEST-only API.
- rumdl, actionlint, shellcheck and zizmor use official archives with pinned hashes in `registerDownload`. Upgrade to a
  release at least seven days old; update all four platform checksums together, using release checksum files or
  verified archives (`gh attestation verify`). Dependabot cannot update these binaries.
- Pin workflow actions by SHA; checkout with `persist-credentials: false`; pass expressions into scripts via `env`.
  Keep caches out of the signing job and expose its push token only to the push step.
- For a dead-code audit, build release and inspect `app/build/outputs/mapping/release/usage.txt`. The block before the
  first `androidx.*` entry shows public members production never reaches; review serialization/Room members by hand.

Gradle 9.8 deprecates `Configuration.setVisible`; several current plugins still call it. Do not suppress warnings.
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
`io.github.astiskala.minimpos`; each ID is tied to the key used for its first upload. Keep the same key and use a higher
version code for each deployment. A signing-key mismatch requires Adyen Support to remove earlier uploads.

Do not bump this repository's version by hand. Use the release workflow for releases; forks must maintain their own
release process, credentials and signing history.

## Release the app

`version.properties` is managed by **Actions › Release › Run workflow** on `main`. Choose patch/minor/major;
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
