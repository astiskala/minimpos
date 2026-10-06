# Contributing to Mini mPOS

For a substantial change, open an issue first to agree on the approach. Keep pull requests focused, describe how you
tested them, and include before/after screenshots for UI changes. Contributions are licensed under [MIT](LICENSE).

## Build and run

You need JDK 17+ to start Gradle (the build downloads JDK 21), Node.js 22+ for setup-helper and release workflow tests,
Android SDK platform
37 and recent build tools.
Android Studio can install the SDK; otherwise set `sdk.dir` in an untracked `local.properties`. The full gate supports
macOS and Linux; its pinned Markdown/workflow linter binaries are not available for Windows.

```sh
./gradlew :app:installDebug
```

Run on an Android 9+ phone or emulator. Away from a terminal, the app defaults to the simulator. Set its outcomes,
delay and printer in Settings › Simulator. Adyen terminals have no adb: use the simulator for debugging or configure
a terminal on your network for integration testing. Follow the merchant [setup guide](docs/getting-started.html).

Android Studio is the simplest editor. The repository's VS Code settings disable the Java extension's Gradle import;
there is no Java code, and that importer cannot handle this build.

## Find the right code

| Module | Responsibility |
| --- | --- |
| `core` | Android-free money/tax, cart, refunds, receipt model/renderers, QR formats, currencies and payment methods. |
| `adyen` | Android-free Terminal, Checkout, Cloud device and Management API integration, transports and simulator. |
| `app` | Compose UI, Room, DataStore, Keystore, email, camera scanning and manual DI in `AppContainer`. |
| `website-test` | Tests and validation for the static website in `docs/`; no app code. |

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
node scripts/gradle.mjs :core:test --tests '*MoneyTest'
node scripts/gradle.mjs qualityGate
```

It overrides console mode to plain and removes only known task/cache progress and passing Node test lines. Warnings,
unknown output, failure diagnostics and the exit status survive. Every run saves complete stdout/stderr in a separate
private log under ignored `build/gradle-logs/`; the runner prints its path. It does not use `--quiet`, disable checks or
truncate failures. Signals are forwarded to Gradle and return a nonzero status. Use regular `./gradlew` for interactive
progress or detailed logging (`--info`, `--stacktrace`). Build/test speed is unchanged; the runner reduces console noise.

The gate checks:

| Check | Contract |
| --- | --- |
| Spotless/ktlint | Kotlin formatting; whitespace and final newlines in other text files. Restart Gradle after changing `.editorconfig`. |
| rumdl | Markdown rules and relative links; 120 columns outside tables/code. Wrap prose by hand; formatting keeps line breaks. |
| Website | Local links/fragments/assets, language and metadata parity, UI labels, screenshots, setup-helper fields and async generation; W3C Nu HTML/CSS validation with no messages. |
| Workflows | actionlint with shellcheck, and offline zizmor. |
| Release | Offline version preparation and exact-commit APK artifact integrity tests. |
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

CI runs the gate first, then builds with `./gradlew :app:assembleRelease -PqualityGatePassed=true` to skip the
build's automatic fatal-only release lint pass. The flag requires `CI=true` and asserts that the gate passed for
this unchanged checkout; it does not record or verify a previous run. Explicit lint checks remain enabled, and
standalone release builds retain release lint by default. R8, signing and packaging checks are never skipped.

### Test setup

- `core` and `adyen`: plain JUnit. Crypto uses independent vectors; TLS tests use a fake Adyen root.
- `app`: Robolectric SDK 33, `TestApplication`, Compose v2 rules and `en-rAU` amounts. See
  [app test pitfalls](app/AGENTS.md#tests). Two worker JVMs run app tests; isolate filesystem fixtures across processes.
- `LocalizationTest` checks resource/format parity and writes receipt samples under
  `app/build/reports/localization/`. `LocalizedUiTest` checks Chinese/Japanese checkout at AMS1 size.
- No real network or DNS in unit tests. Use the existing fake terminal, cloud, Payments app, Management and link APIs.

### Known integration verification gaps

There is no real Adyen test account in CI. Still needing real-device/API verification: cloud offline/busy event
notifications; Payments app return-URL encoding, error answers and size limits; TEST payment-link paid/PATCH answers
and line-item validation; whether a shopper reference without `recurringProcessingModel` stores no card;
Management store receipt details and read permissions; terminal discovery, reported network addresses and shared-key
settings (including inherited settings and sensitive-field permissions).
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
- Pre-launch schemas and QR formats have one current contract. Update models, exported Room schema, tests and setup
  helper together, without earlier-build compatibility. Never add destructive fallback or reset local data silently.
- New-install localized/regional defaults must not overwrite stored text or imported settings.

## Website and screenshots

GitHub Pages publishes `docs/` unchanged. Keep the existing visual design and script-free guides; only the setup
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
  Keep caches out of release jobs and expose its push token only to the push step.
- For a dead-code audit, build release and inspect `app/build/outputs/mapping/release/usage.txt`. The block before the
  first `androidx.*` entry shows public members production never reaches; review serialization/Room members by hand.

Gradle 9.8 deprecates `Configuration.setVisible`; several current plugins still call it. Do not suppress warnings.
To see configuration-time warnings hidden by a reused configuration cache:

```sh
./gradlew qualityGate --no-configuration-cache --warning-mode all -Dorg.gradle.deprecation.trace=true
```

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
the workflow commits the new version first and explicitly starts CI on it. CI runs the full quality gate and R8
build, then saves the unsigned APK with its commit, version, run attempt and SHA-256. Release waits up to 45 minutes
for successful CI on that exact version commit. Missing, failed or cancelled CI blocks publication.

Only successful CI allows the release job to access the signing environment. It verifies the artifact's provenance,
checksum, application ID and version, signs CI's APK without rebuilding it, checks its signature and alignment, tags
the verified commit as `vX.Y.Z`, and publishes the APK and notes. The full quality gate runs once, in CI; no Gradle
plugins execute with signing secrets. Main must still point to the verified commit before tagging.

A failed attempt can leave an unpublished version commit on main. Choose **resume** to rerun CI and publish it
without another bump; the version must come from a version-only `Release X.Y.Z` commit without an existing tag.
Later fixes can follow that commit before resuming. If tagging
succeeded but publication failed, finish publication for that existing tag rather than bumping again. CI artifacts
expire after 14 days; resume produces a fresh one. Only the latest release is maintained.

The repository's `release` environment is restricted to `main`. Its secrets are `RELEASE_KEYSTORE` (base64 keystore)
and `RELEASE_SIGNING_PROPERTIES` (`storePassword`, `keyAlias`, `keyPassword`). Never commit them or local SDK/signing
files. See [SECURITY.md](SECURITY.md) for private vulnerability reporting.
