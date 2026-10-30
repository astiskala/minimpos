# Screenshot testing

Golden images are the visual safety net for [DESIGN.md](../../DESIGN.md) tokens and shared components. They compare
approved Android rendering with future Android rendering; renders from external design tools show intent only and are
never baselines. Recording and review steps are in CONTRIBUTING's
[procedure](../../CONTRIBUTING.md#update-the-golden-screenshots).

## How it works

- **Roborazzi** (test-only; release builds and R8 are unaffected) captures Compose tests under Robolectric with native
  graphics, so rendered pixels are real. It reuses the existing runner, `TestEnvironment`, fakes and device-size
  helpers; Paparazzi cannot render `AppContainer`-backed screens and Compose Preview Screenshot Testing needs
  `@Preview`s, which the app does not have.
- `ScreenshotRule`, beside `createRecordingComposeRule`, names each capture from test class, method and device.
  Goldens are committed under `app/src/test/screenshots/` and are a declared test input, so a changed PNG reruns the
  comparison.
- `scripts/dev check ui-snapshots` compares; `scripts/dev snapshots record` rewrites. The comparison is not part of
  `scripts/dev finish`.
- Rendering is deterministic: fixed `Locale`, `TimeZone` and `fontScale`, disabled animations,
  `UnconfinedTestDispatcher` per [app/AGENTS.md](../../app/AGENTS.md#tests), `en-rAU` amounts, no live clocks or
  network, and synthetic stored facts instead of simulator-generated references, times and card details.
- `createRecordingComposeRule` separately writes unmerged trees and root PNGs for **failing** UI tests to
  `app/build/reports/ui-failures/`. The documentation screenshots (`scripts/docs-capture`) are website assets, not
  tests.

## Tolerance and CI

- 0 % changed pixels for the component gallery, 0.1 % for whole screens. Roborazzi first ignores colour distances up to
  `0.007`, so these count pixels, not every raw RGB difference.
- Anti-aliasing differs between operating systems and JVMs: Linux and macOS renders differ by up to 2 levels per channel
  at edges. CI's `ui-snapshots` job (`ubuntu-latest`, Temurin 21) is authoritative and its goldens are recorded there;
  a local comparison on another platform can fail for that reason alone. The job uploads expected, actual and diff
  images on failure, and its manual recording option uploads goldens for review.

## Coverage

| Test | Subjects | Variants |
| --- | --- | --- |
| `DesignGalleryTest` | Shared components: buttons in every variant, compact actions, `ActionGroup`, chips and transaction rows, `Card` and notices, text roles, feedback and status badges, outcome header, `EmptyState`, `SwitchRow` and a text field with an error, `Keypad`, confirm dialog; also checks enabled touch targets | Regular, Medium, AMS1 |
| `ScreenGoldensTest` | Home (also when setup is required), sale and cart, cash entry, results (approved, declined, unknown), History list and detail, reconciliation totals, refund reasons | AMS1, S1F2, regular phone, tablet two-pane |
| `SettingsGoldensTest` | Settings list, terminal setup, currency and terminal pickers, email, receipt, security and tax settings, admin PIN, products and price errors, transfer, receipt import review, integration support | Regular phone, AMS1, 1.5× font scale, tablet two-pane |
| `LocaleGoldensTest` | Home and the terminal picker | `fr-FR`, `ja` on AMS1 |
| `UpdateGoldensTest` | Home with an update offer | AMS1 |

Device sizes reuse `SmallScreenTest`'s: **AMS1** `w320dp-h460dp-hdpi`, **S1F2** 360×640, **regular phone** 411×891
and **tablet** 1000×1280 for two-pane scenes (the expanded threshold is 840 dp). Use at most three per screen. Dark mode
and dynamic colour are not covered because the app is light only.

Not covered yet: checkout review at 1.5×, Japanese results and History's empty state.

## Keeping the set healthy

- A deliberate UI change re-records the affected goldens and updates `DESIGN.md` when a visual rule changed. A new
  shared component adds a gallery capture.
- Keep the set focused, not hundreds of images: each PNG is about 30–60 KB in git.
- Compose and Material 3 upgrades (Dependabot BOM bumps) can change many goldens at once; review them as one change.
