# Contributing to Mini mPOS

Thanks for helping out! Mini mPOS is a small project, so the process is light. For anything bigger than a bug fix,
please open an issue first so we can agree on the approach before you spend time on it.

## Set up

You need:

- JDK 17 or later to start Gradle. The build then downloads and uses JDK 21 itself
  (`gradle/gradle-daemon-jvm.properties`).
- The Android SDK with platform 37 and recent build tools. Android Studio installs these for you; otherwise point
  `sdk.dir` in `local.properties` at your SDK.
- macOS or Linux for the full quality gate: it downloads the release binaries of the Markdown and workflow linters
  (checked against pinned SHA-256 checksums), which are built for those systems only.
- An emulator or Android phone (Android 9 or later) to run the app. Without an Adyen terminal the app uses its
  built-in simulator.

```sh
./gradlew :app:installDebug   # build and install on the connected device or emulator
```

Android Studio is the easiest editor. In VS Code, the project's settings turn off the Java extension's Gradle import:
there is no Java code, and the import fails on this build with errors in every `build.gradle.kts`.

You can't debug over USB on Adyen terminals, so all development happens against the simulator. To test with a real
terminal, set Settings › Terminal › Payments go to › A terminal on your network, then enter the terminal's IP address,
its terminal ID (POIID) and its shared key. On a terminal itself only the shared key is asked for.

## Check your change

Run the full quality gate before you open a pull request:

```sh
./gradlew qualityGate
```

It runs:

- Spotless with ktlint (formatting), and trailing whitespace and final newlines in the other text files. Fix
  formatting with `./gradlew spotlessApply`, which also fixes what it can in the Markdown files. ktlint caches
  `.editorconfig` inside the Gradle daemon, so after editing it run `./gradlew --stop` before checking again.
- [rumdl](https://rumdl.dev) (`.rumdl.toml`) on every Markdown file: markdownlint's rules, lines of at most 120
  characters outside tables and code, and relative links that point at existing files. It keeps your line breaks, so
  wrap prose yourself.
- The website's checks (`website-test`): tests of its links, languages, metadata, quoted app labels and screenshots,
  and the [W3C Nu Html Checker](https://validator.github.io/validator/) on its HTML and CSS.
- The GitHub workflows' checks: actionlint (with shellcheck on their scripts) and zizmor's security audit.
- detekt with the Compose rules (`config/detekt/`), including the documentation rules described below. There is no
  baseline: every finding must be fixed.
- A KDoc link check: Dokka builds each module's documentation (`dokkaGeneratePublicationHtml`, part of `check`) and
  fails on any `[link]` that does not resolve, in public and private code alike. The generated pages are in
  `<module>/build/dokka/html` if you want to read them.
- Android Lint in every module, with all checks enabled (including the ones that are off by default), test sources
  included and warnings treated as errors.
- Unit tests, Robolectric tests and Compose UI tests.
- Architecture tests (`ArchitectureTest` in each module, using ArchUnit). They keep `core` and `terminal-api` free of
  Android and dependency cycles, keep the app's layers apart (business logic free of Compose, Room only in `data`,
  secrets encrypted only in `data.security`), keep money out of floating-point types, stop anything from using the
  Adyen library's Apache HTTP client or its unencrypted TEST-only API, and forbid logging. They also give each
  decision one home (where a payment stands, where payments go, how outcomes are worded, …), as
  [`app/AGENTS.md`](app/AGENTS.md) and [`terminal-api/AGENTS.md`](terminal-api/AGENTS.md) list.
- An API level check (`AndroidApiLevelTest`): `core` and `terminal-api` run on Android 9 terminals, but Lint doesn't
  check JVM modules, so the test checks every Java API they use against the Android SDK's API database.
- Kover coverage thresholds: `core` 95% lines and 85% branches, `terminal-api` 90% and 75%, `app` (non-UI) 80% lines.
- A manifest check against Adyen's app requirements (minimum Android version, allowed permissions, no home-screen or
  test-only flags).

Kotlin warnings are errors in every module. It's also worth building the release APK
(`./gradlew :app:assembleRelease`) when you add a dependency, because R8 may need keep rules for it. GitHub Actions
runs the quality gate and the release build on every pull request (`.github/workflows/ci.yml`), and Dependabot proposes
dependency updates (Gradle and GitHub Actions) once they are a week old. Each push to `main` also submits the
resolved Gradle dependency graph, so Dependabot alerts cover transitive dependencies too.

### Rules are fixed, not silenced

Don't add `@Suppress`, lint ignores, detekt baselines or rule exclusions to get a change through. The few that exist
are there because the rule can't be satisfied (third-party or generated code, or a rule that doesn't fit Compose), and
each one has a comment explaining why. If you think a rule is wrong for your case, say so in the pull request.

## Guidelines

- **Use the project's words.** `CONTEXT.md` defines the domain terms (sale, pre-authorization, standing, destination,
  delivery, …) and where each is decided; name new code after them.
- **Keep the modules pure.** `core` and `terminal-api` are plain Kotlin with no Android dependencies, so they stay fast
  to test. Android code lives in `app`.
- **Money is `Long` minor units** with `CurrencySpec`, never `Double`. Tax rates are thousandths of a percent.
- **Use Adyen's library** for anything the Terminal API needs (models, encryption, certificate checks) instead of
  writing it yourself. [`terminal-api/AGENTS.md`](terminal-api/AGENTS.md) lists what it takes to make that library
  work on Android. The Checkout API calls (captures, authorization adjustments and payment links) and the
  cloud transport are the exception: they post plain JSON with OkHttp, because the library's Checkout and cloud models
  need Jackson and keep rules for hundreds of classes.
- **Respect the terminal's rules.** Don't add permissions (only internet, network state and camera are allowed), don't
  raise `minSdk` above 28, and don't depend on Google Play services, which Adyen terminals don't have.
- **Add dependencies through `gradle/libs.versions.toml`**, pin exact versions, and prefer releases that are at least a
  week old.
- **Never log or store secrets in plain text.** Passphrases, API keys and passwords go through `SecretStore`, which
  encrypts them with the Android Keystore.
- **Put user-facing text in `strings.xml`**, in short plain US English sentences, and add the Simplified Chinese
  (`values-zh-rCN`) and Japanese (`values-ja`) translations in the same change. Chinese and Japanese plurals only use
  `other`. Keep Adyen's Customer Area menu paths (such as Devices › Device settings) in English in every language.
- **Document the code.** Every public or protected class, object (companion objects too), function, property and
  enum entry needs a KDoc comment (detekt checks this). Say what it's for, and include units (minor units, thousandths
  of a percent), what `null` means, threading, errors (`@throws`) and Adyen or format details a maintainer would
  otherwise have to look up. Don't repeat the name ("The name."). For data classes, use `@property` tags or document
  each property. If a class's KDoc has tags for its constructor, it needs one for every constructor parameter, in
  declaration order: `@property` for public properties, `@param` for everything else (private `val`s included), or
  document parameters with their own KDoc instead of tags. Refer to other code with `[links]`, which the build checks.
  In private code, add a comment only where the reason isn't obvious from the code.
- **Keep composables small.** detekt's length and complexity limits apply to Compose code too. Split a long screen
  into private composables: make them `ColumnScope` or `RowScope` extensions when they emit several siblings, so the
  parent's spacing still applies, and pass state and callbacks rather than the view model.

## Tests

- `core` and `terminal-api` have plain JUnit tests. The Terminal API tests cover encryption against independently
  generated test vectors, TLS against a fake Adyen root certificate, and the simulator.
- `app` uses Robolectric for data, view model and Compose UI tests (`app/src/test`). UI tests run with the
  `en-rAU` locale, so amounts show as `$4.50`.
- `LocalizationTest` checks that every string has its translations with the same format arguments, and writes sample
  receipts in each language to `app/build/reports/localization/`. `LocalizedUiTest` runs checkout in Chinese and
  Japanese at the AMS1's screen size.
- [`app/AGENTS.md`](app/AGENTS.md) describes the test helpers (`TestEnvironment` and the fake terminal, cloud and
  Payments app) and the Robolectric pitfalls to avoid, such as text fields in an `AlertDialog`.

## Changing stored data

- **Database:** raise the version in `AppDatabase`, add an `AutoMigration` (or a manual migration), and commit the new
  schema file that the build writes to `app/schemas/`. Extend `DatabaseMigrationTest` so existing data is checked.
- **Settings:** new fields in `AppSettings` need default values, so settings saved by older versions still load.
- **QR formats:** the catalog and refund QR codes are read by other devices, which may run an older version. If
  you change a format, bump its version number and keep decoding the older versions. The setup helper page
  (`docs/js/setup.js`) writes transfer codes too: keep it in step with `TransferCodec`, `QrChunks` and `TransferSeal`,
  and regenerate the codes in `SetupTransferTest`'s setup helper test when its output changes.

## Screenshots

The screenshots in `docs/images/` come from an emulator running Android 13 in English (Australia), with a demo café
catalog and the simulator. The status bar was cleaned up with Android's system UI demo mode. The website shows them in
S1F2 and AMS1 frames, so capture them at those screens' sizes: 720×1280 (S1F2, resized to 540×960) and 480×800 (AMS1,
only `sale-ams1.png`). Keep the demo data consistent, so the README and the website match. The full recipe (emulators,
demo data, the flows behind each screenshot, the social image) is in
[`.devin/skills/docs-screenshots/SKILL.md`](.devin/skills/docs-screenshots/SKILL.md).

The website in `docs/` has English, Simplified Chinese (`docs/zh-CN/`) and Japanese (`docs/ja/`) pages, which all use
the English screenshots. Change all three languages together and check them with `./gradlew :website-test:check` (part
of the quality gate): links, language switches, metadata, the app labels the guides quote, and valid HTML and CSS
(void elements such as `<img ...>` take no trailing slash). Only the setup helper pages run scripts, and only the two
in `docs/js/`: `setup.js` and `qrcodegen.js`, a vendored copy of Project Nayuki's QR Code generator (its header says
which release and how it was compiled; replace the whole file to upgrade it).

## Releases

The version lives in `version.properties`; don't change it in pull requests. To release, the maintainer runs
**Actions › Release › Run workflow** on `main` and picks which part of the version to raise (patch, minor or major; the
`versionCode` always goes up by one, as the Customer Area needs). The workflow runs the quality gate, builds the APK
signed with the upload key, commits and tags the new version (`vX.Y.Z`) and publishes a GitHub Release with the APK and
generated release notes.

The signing key is kept in the repository's `release` environment, which only `main` can use, as two secrets:
`RELEASE_KEYSTORE` (the keystore, base64-encoded) and `RELEASE_SIGNING_PROPERTIES` (the `storePassword`, `keyAlias`
and `keyPassword` lines of `keystore.properties`).

## Pull requests

- Keep each pull request focused on one change, and describe what you changed and how you tested it.
- Include before-and-after screenshots for UI changes.
- Make sure `./gradlew qualityGate` passes.
- Don't commit secrets, keystores, `keystore.properties` or `local.properties`.

By contributing, you agree that your contributions are licensed under the project's [MIT license](LICENSE).
