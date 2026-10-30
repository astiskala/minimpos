# Mini mPOS

Kotlin Android POS for Adyen Android terminals, also usable on tablets and phones with a network/cloud terminal or the
Adyen Payments app (Tap to Pay). There is no backend. The built-in simulator needs no Adyen account.

## Where things live

| Path | Contents | Local rules |
| --- | --- | --- |
| `core/` | Android-free money/tax, cart, refunds, receipts, QR formats, currencies, payment methods, original payment context. | `core` `ArchitectureTest` |
| `adyen/` | Android-free Terminal, Checkout, Cloud device and Management integration, transports, simulator. | [adyen/AGENTS.md](adyen/AGENTS.md) |
| `app/` | Compose UI, Room, DataStore, Keystore, email, camera scanning; manual DI in `AppContainer`. | [app/AGENTS.md](app/AGENTS.md) |
| `docs/` | Static merchant website in seven languages and the offline setup helper. | [Documentation changes](#documentation-changes) |
| `website-test/` | Website and setup-helper tests; no app code. | |
| `tooling/` | Kotlin/JVM release CLI, signing-property extraction and shell-script regression tests. | |
| `build-logic/` | Convention plugins with the Kotlin/JVM modules' shared build setup. | |
| `scripts/` | Gradle/dev runners, CI entry point, docs capture, release verification. | |
| `.stitch/` | Visual design specification (`DESIGN.md`) for agents and Google Stitch. | [Design system](#design-system) |
| `security/` | STRIDE threat model. | [minimpos-security-review](.agents/skills/minimpos-security-review/SKILL.md) |
| `.agents/skills/` | Agent skills (`SKILL.md` with name/description front matter) for any coding agent. | |

Nested `AGENTS.md` files add module rules to this file and never relax it. Their starred rules are enforced by ArchUnit.
Read the module file before changing that module. Read the rest only when the task needs it:

- [CONTEXT.md](CONTEXT.md): domain vocabulary and decision owners. Use its terms; add a new concept there before naming
  a module.
- [CONTRIBUTING.md](CONTRIBUTING.md): command behavior, test setup, dependency, website and release procedures.
  KDoc holds detailed code contracts.
- [minimpos-iteration](.agents/skills/minimpos-iteration/SKILL.md) (change recipes) and
  [minimpos-tests](.agents/skills/minimpos-tests/SKILL.md) (test starters). Use
  [minimpos-security-review](.agents/skills/minimpos-security-review/SKILL.md) for security reviews. Use
  [docs-screenshots](.agents/skills/docs-screenshots/SKILL.md) only when capturing screenshots or running the AMS1 emulator.
- `README.md` and `docs/`: merchant documentation. [SECURITY.md](SECURITY.md): reporting and data protection.

## Commands

Gradle resolves all dependencies; there is no separate install step. Prerequisites are in CONTRIBUTING's
[Build and run](CONTRIBUTING.md#build-and-run). Use `scripts/gradle <tasks/flags>` instead of `./gradlew` for build
and test runs: concise output, a full private log under `build/gradle-logs/` and an unchanged exit status.

| Purpose | Command |
| --- | --- |
| Check JDK, SDK and Chrome | `scripts/dev preflight` |
| Install a debug build | `scripts/gradle :app:installDebug` |
| Run matching tests | `scripts/dev test MODULE 'PATTERN'` (`core`, `adyen`, `app`, `website-test`, `tooling`) |
| Run a focused lane | `scripts/dev check AREA` (module, `architecture`, `localization`, `ui`, `db`, `payment`, `website`…) |
| Format (ktlint, Markdown, whitespace) | `scripts/dev format` |
| Full gate: format, then `qualityGate --continue` | `scripts/dev finish` |
| After dependency changes | `scripts/gradle :app:assembleRelease` |

- Run one Gradle invocation at a time per checkout. Format after an edit batch, never concurrently with edits.
- Fast lanes are feedback, not completion evidence. Do not routinely run `clean` or `--rerun-tasks`.
- On failure, read the `repair.txt` and log the runner prints; `scripts/dev report` adds JUnit details.
- After changing `.editorconfig`, run `./gradlew --stop` so ktlint reloads it.

## Done means

- `scripts/dev finish` passes on the final checkout. The gate fails on Kotlin warnings, Android Lint warnings, detekt
  findings, unresolved KDoc links, Markdown/workflow/shell linters, HTML checker messages, coverage bounds and ArchUnit.
- Dependency changes also pass the release build: R8 may need keep rules.
- Fix findings, don't silence them: no new suppressions, lint ignores, baselines or rule exclusions. Do not suppress
  plugin deprecations either; investigate with the command in CONTRIBUTING.
- Simulator and fake coverage do not prove real-Adyen behavior; report unverifiable gaps honestly.
- GitHub release notes follow [the release-note style](CONTRIBUTING.md#release-note-style). Read the tagged changes,
  write concise merchant-facing notes, and supply them when dispatching Release; generated changelogs are not notes.

## Non-negotiable constraints

- From 1.0.0 onward, schema changes require versioned, data-preserving migrations and upgrade tests. Retain released
  Room schemas; update the new schema and its tests together. Preserve supported Android versions, Adyen protocols,
  validation, encryption, transaction recovery and idempotency. Never reset local data or add destructive database
  fallback without approval.
- Never change `applicationId` (`app.minimpos`) or raise `minSdk` above 28. Terminals have no Google
  Play services and no adb. Smallest usable screen is AMS1, about 320×460 dp.
- Only INTERNET, ACCESS_NETWORK_STATE and CAMERA permissions; no CATEGORY_HOME or `testOnly`. The application icon
  must be PNG, not adaptive; `MainActivity` has a separate adaptive icon. Both manifest variants are checked.
- Money is `Long` minor units with `CurrencySpec`; Adyen's currency decimals win over ISO. Tax rates are thousandths
  of a percent. No floating-point money or separate country/region setting.
- Secrets go through `SecretStore`; never log them, payment data or requests. Do not commit keys, keystores,
  `keystore.properties` or `local.properties`. No logging in production modules.
- Commits use Adam Stiskala <github@adamstiskala.com>, not the Adyen work email, with no bot attribution.

## Do not edit by hand

- `version.properties`: only the Release workflow changes it; see CONTRIBUTING's
  [Release the app](CONTRIBUTING.md#release-the-app).
- Exported Room schemas in `app/schemas/`: the build writes them. Never alter a released schema.
- Released stored-format fixtures in `app/src/test/resources/stored/<version>/`; add a new version's directory instead.
- Vendored `docs/js/qrcodegen.js`, generated `gradle/gradle-daemon-jvm.properties` and the Gradle wrapper files.

## Code conventions

- A new architectural decision needs its ArchUnit rule in the same change, a reason comment and a deliberate-violation
  check. Keep every Kotlin source set covered by type-resolved detekt; aggregate app coverage must not compile release.
- Public/protected declarations need useful KDoc (tests exempt): units, null meaning, threading, formats, errors.
  Once constructor tags are used, cover every parameter in order (`@property` for public properties, otherwise `@param`).
- QR contracts have one current format. Change the app, setup helper, format tests and helper test vectors together.
  `docs/js/setup.js` mirrors `TransferCodec`, `QrChunks` and `TransferSeal`; credentials stay local. CSP network
  exceptions are limited to the documented hosting security/performance endpoints.
- The release's `update.json` asset has one current format. Change the app, the Release workflow and their tests
  together; it is read only on devices that are not Adyen terminals.

## Payment and product boundaries

- The device runs Mini mPOS; the terminal takes the card. Keep these distinct in user text.
- Checkout API setup is required before real payments, even ordinary sales. Until a connection check verifies the
  setup, every action reaching Adyen or a terminal (refunds, captures, printing, reconciliation, capabilities, Adyen
  imports included) goes through the setup gate, which explains generically and links to Settings › Terminal; setup
  tools and status checks of unfinished operations never wait. Saved-card charges and their refunds go through the
  Checkout API. Select TEST/LIVE first for network/cloud terminals and Tap to Pay; on-device terminals supply
  their environment. Tap to Pay opens only the selected environment's app; both apps may be installed.
  Never probe API credentials across environments; changing destination clears it unless the saved
  API key and merchant account it belongs to stay.
- Every real setup requires the same primary API credential roles, including API credential management and Cloud
  Device API. Only the separate Payments app boarding credential requires `Adyen Payments App role`.
- Persist each financial operation before sending. A missing answer means unknown, not failed. Retry the same logical
  operation with its stored identity and request facts; a distinct authorization renewal needs a new identity.
  Historical actions must validate the original payment context against current credentials.
- Explicit LIVE/TEST/simulator switch confirmation purges all local history, even unfinished operations; warn about
  lost recovery again when any exist. Ordinary history clearing still preserves unfinished operations.
- No backend by design. Refund/capture requests and asynchronous adjustments are not final confirmation; the Customer
  Area is authoritative. Paid links have no PSP reference available to the app and are refunded there.
- Tap to Pay uses an encrypted shared key too. The Payments app has no printer, abort or diagnosis. Status recovery
  needs a supporting app version and Adyen enablement; inconclusive checks keep the result unknown. Simulated payment
  links stay offline and clearly label their screen and delivered receipts as demos; their QR codes cannot take payments.
- Optional Manager PIN approval is separate from admin access and must be checked again before financial operations.
- Keep deliberate UI choices: no TEST banner (only simulator), no Home terminal-status line or unprotected-settings
  warning. Home keeps both full-width sale/pre-authorization tiles, then Tokens/Gift cards, Refund/Cashout and
  History/Reconciliation, in row-major order. The top-right Settings icon opens main Settings
  under the admin lock; Products lives there. Gated tiles stay pressable and explain; only the setup card is shown.
- Ask the shopper's email on the terminal only once emailed receipts are ready (SMTP set up, capture not Never); a
  terminal email is sent automatically like a checkout one.
- Every product has a tax rate; use 0% for untaxed items. Turning tax off keeps rates. The last rate cannot be deleted.
- Customer reference is asked for only as the shopper reference. Card saving needs a shopper reference and consent.
  New installations use Customer reference as the shopper reference, no extra transaction reference; automatic printing
  is on.
- API keys on the device are a documented trade-off, not a bug to solve by adding a backend. Mobile SDK card readers
  are deliberately not integrated.

## User text and localization

- User text is short US English; keep identifier/stored-value spelling and Adyen text unchanged. Update English,
  Mexican Spanish (`es-MX`), French (`fr-FR`), Japanese, Brazilian Portuguese (`pt-BR`), Simplified Chinese and Hong
  Kong-style Traditional Chinese (`zh-Hant`) together, with matching resource keys/format arguments.
  CJK plurals use only `other`.
  Customer Area paths stay English (quote UI names with “…” in Chinese and 「…」 in Japanese).
- Language switchers (app Settings, website, README) name each language in itself, following CLDR (`Español (México)`,
  `Français (France)`, `简体中文`, `繁體中文`), and list them in BCP 47 tag order: `en`, `es-MX`, `fr-FR`, `ja`, `pt-BR`,
  `zh-CN`, `zh-Hant`. A new language goes at its tag's position everywhere.
- New-install defaults may depend on country/language; never overwrite saved merchant text or imported catalogs.
  Receipt labels are read at delivery, Adyen receipt fields stay verbatim. Keep `StarterTax`'s rates current.
- Group `strings.xml` by feature with `<!-- ... -->` section comments; keep the same sections and key order in every
  language; add new strings beside related existing strings instead of appending a new block at the end.

## Design system

- [.stitch/DESIGN.md](.stitch/DESIGN.md) is the authoritative visual specification; `Theme.kt` and `Tokens.kt` in
  `app/.../ui/theme` implement it. Read it before changing UI and keep it, the tokens and the screenshots in step.
- Use `MaterialTheme.colorScheme`, `shapes` and `typography`, `LocalStatusColors`, `LocalDimens`, `Spacing`, `IconSize`
  and `Elevation`. Do not add `Color(0x…)`, `RoundedCornerShape` or new fixed text sizes to feature code; add or
  change a token in the theme and `DESIGN.md` instead.
- Prefer `ui.components` (buttons, `Card`, text roles, dialogs, `MiniScaffold`) over raw Material equivalents or copies.
- Record a deliberate exception in `DESIGN.md` › Known Exceptions with its reason; resolve audit items
  ([docs/ui/DESIGN_AUDIT.md](docs/ui/DESIGN_AUDIT.md)) only when they are marked safe or the owner decides.
- `scripts/design-audit` reports bypasses; `--strict` fails on colour and shape literals. Materially changed UI updates
  or adds screenshot coverage once it exists ([strategy](docs/ui/SCREENSHOT_TESTING.md)); until then attach before/after
  screenshots as CONTRIBUTING asks.

## Documentation changes

- Give each fact one authoritative home for its reader; link instead of copying. Merchant guides are Quick start,
  Using Mini mPOS and Troubleshooting. Setup guides branch by destination; operations describe tasks, not every field.
- Preserve the site's visual design. Update all seven languages, reciprocal switches, canonical/hreflang metadata and
  website tests together. Only setup helpers author scripts; hosting may inject its documented scripts. Authored assets
  are local. HTML void elements have no trailing slash.
- Explain Mini mPOS's requirements; link to Adyen for Adyen account/platform procedures. Avoid older-app warnings,
  country-specific tax compliance, invoice requirements and country-specific receipt defaults.
- Screenshots are disclosed simulator demos in the page's supported language and original terminal frames.
- Prose is wrapped by hand at 120 columns; `scripts/dev format` fixes what it can and the gate checks relative links.

## Maintaining these instructions

Keep this file small: retain actionable constraints and non-obvious decisions, not feature inventories, code tours,
historical fixes or copies of contributor instructions. Put module-specific pitfalls in the module's file and command
behavior in CONTRIBUTING.
