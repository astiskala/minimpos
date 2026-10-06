# Mini mPOS

Android POS for Adyen Android terminals, also usable on tablets and phones with a network/cloud terminal or the
Adyen Payments app (Tap to Pay). The built-in simulator needs no Adyen account.

## Read only what the task needs

- Before changing `app/` or `adyen/`, read that module's `AGENTS.md`. Their starred rules are enforced by ArchUnit.
- `CONTEXT.md`: domain vocabulary and decision owners. Use its terms; add a new concept there before naming a module.
- `CONTRIBUTING.md`: development, verification, dependency maintenance and releases. KDoc: detailed code contracts.
- `README.md` and `docs/`: merchant documentation. `SECURITY.md`: reporting and data protection.

## Non-negotiable constraints

- Pre-launch, with no users yet: keep one current model, format and set of defaults. No earlier-build aliases,
  migrations or compatibility branches. Preserve supported Android versions, Adyen protocols, validation,
  encryption, transaction recovery and idempotency. Never reset local data or add destructive database fallback
  without approval; update the current Room schema and its tests together.
- Never change `applicationId` (`io.github.astiskala.minimpos`) or raise `minSdk` above 28. Terminals have no Google
  Play services and no adb. Smallest usable screen is AMS1, about 320×460 dp.
- Only INTERNET, ACCESS_NETWORK_STATE and CAMERA permissions; no CATEGORY_HOME or `testOnly`. The application icon
  must be PNG, not adaptive; `MainActivity` has a separate adaptive icon. Both manifest variants are checked.
- Money is `Long` minor units with `CurrencySpec`; Adyen's currency decimals win over ISO. Tax rates are thousandths
  of a percent. No floating-point money or separate country/region setting.
- Secrets go through `SecretStore`; never log them, payment data or requests. Do not commit keys, keystores,
  `keystore.properties` or `local.properties`. No logging in production modules.
- Fix findings, don't silence them: no new suppressions, lint ignores, baselines or rule exclusions.
- Do not change `version.properties` by hand. Releases use the Release workflow; see `CONTRIBUTING.md`.
- Commits use Adam Stiskala <github@adamstiskala.com>, not the Adyen work email, with no bot attribution.

## Payment and product boundaries

- The device runs Mini mPOS; the terminal takes the card. Keep these distinct in user text.
- Checkout API setup is required before real payments, even ordinary sales. Connection checks, refunds and printing
  need only the destination. Select TEST/LIVE first for network/cloud terminals; on-device terminals and the Payments
  app supply their environment. Never probe API credentials across environments; changing destination clears it.
- Persist each financial operation before sending. A missing answer means unknown, not failed. Retry the same logical
  operation with its stored identity and request facts; a distinct authorization renewal needs a new identity.
  Historical actions must validate the original payment context against current credentials.
- No backend by design. Refund/capture requests and asynchronous adjustments are not final confirmation; the Customer
  Area is authoritative. Paid links have no PSP reference available to the app and are refunded there.
- Tap to Pay uses an encrypted shared key too. The Payments app has no printer, abort, diagnosis or remote status
  lookup; only an already received late reply can resolve a missing answer. Simulated payment links stay offline and
  clearly label their screen and delivered receipts as demos; their QR codes cannot take payments.
- Optional Manager PIN approval is separate from admin access and must be checked again before financial operations.
- Keep deliberate UI choices: no TEST banner (only simulator), no Home terminal-status line or unprotected-settings
  warning. Home keeps both full-width sale/pre-authorization tiles, half-width Refund/History, slim Products/Settings.
- Every product has a tax rate; use 0% for untaxed items. Turning tax off keeps rates. The last rate cannot be deleted.
- Customer reference is asked for only as the shopper reference. Card saving needs a shopper reference and consent.
  No shopper reference, extra transaction reference or card saving on a new installation; automatic printing is on.
- API keys on the device are a documented trade-off, not a bug to solve by adding a backend. Mobile SDK card readers
  are deliberately not integrated. Real-Adyen verification gaps are listed in `CONTRIBUTING.md`.

## Editing and verification

- Run `./gradlew qualityGate` after changes; `./gradlew spotlessApply` fixes formatting, including Markdown.
  After changing `.editorconfig`, stop the Gradle daemon so ktlint reloads it. Build release after dependency changes.
- For agent build/test runs, use `node scripts/gradle.mjs <tasks/flags>` (see `CONTRIBUTING.md`): concise output,
  full private logs and unchanged exit status. Read relevant log sections on failure; never suppress warnings.
- Kotlin warnings, Android Lint warnings, detekt findings, unresolved KDoc links and HTML checker messages fail the
  gate. Do not suppress plugin deprecations either; investigate with the command in `CONTRIBUTING.md`.
- A new architectural decision needs its ArchUnit rule in the same change, a reason comment and a deliberate-violation
  check. Keep every Kotlin source set covered by type-resolved detekt; aggregate app coverage must not compile release.
- Public/protected declarations need useful KDoc (tests exempt): units, null meaning, threading, formats, errors.
  Once constructor tags are used, cover every parameter in order (`@property` for public properties, otherwise `@param`).
- User text is short US English; keep identifier/stored-value spelling and Adyen text unchanged. Update English,
  Simplified Chinese and Japanese together, with matching resource keys/format arguments; CJK plurals use only `other`.
  Customer Area paths stay English (quote UI names with “…” in Chinese and 「…」 in Japanese).
- New-install defaults may depend on country/language; never overwrite saved merchant text or imported catalogs.
  Receipt labels are read at delivery, Adyen receipt fields stay verbatim. Keep `StarterTax`'s rates current.
- QR contracts have one current format. Change the app, setup helper, format tests and helper test vectors together.
  `docs/js/setup.js` mirrors `TransferCodec`, `QrChunks` and `TransferSeal`; retain its no-network CSP.
- The release's `update.json` asset has one current format. Change the app, the Release workflow and their tests
  together; it is read only on devices that are not Adyen terminals.

## Documentation changes

- Give each fact one authoritative home for its reader; link instead of copying. Merchant guides are Getting started,
  Using Mini mPOS and Troubleshooting. Setup guides branch by destination; operations describe tasks, not every field.
- Preserve the site's visual design. Update all three languages, reciprocal switches, canonical/hreflang metadata and
  website tests together. Only setup helpers run scripts; all assets are local. HTML void elements have no trailing slash.
- Explain Mini mPOS's requirements; link to Adyen for Adyen account/platform procedures. Avoid older-app warnings,
  country-specific tax compliance, invoice requirements and country-specific receipt defaults.
- Screenshots are disclosed English simulator demos in original terminal frames. Use
  `.devin/skills/docs-screenshots/SKILL.md` only when capturing screenshots or running the AMS1 emulator.
- Keep this file small: retain actionable constraints and non-obvious decisions, not feature inventories, code tours,
  historical fixes or copies of contributor instructions. Put module-specific pitfalls in the module's file.
