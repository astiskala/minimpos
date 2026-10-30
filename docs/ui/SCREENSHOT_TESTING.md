# Screenshot testing strategy

The baseline is implemented as described in [Status](#status); the rest of this page is still the plan. It extends what
the repository already does.

## Status

- **Done:** Roborazzi 1.77.0 is test-only; release builds and R8 are unaffected. `ScreenshotRule` sits beside
  `createRecordingComposeRule`. There are 59 goldens under `app/src/test/screenshots/`, across `DesignGalleryTest`,
  `ScreenGoldensTest`, `SettingsGoldensTest`, `LocaleGoldensTest` and `UpdateGoldensTest`. They cover shared components,
  Home, sale and cash entry, results, processing, History list/detail, Settings and pickers, PIN entry, products,
  transfer controls/review, receipt import and integration support details. Variants include AMS1, S1F2, regular phones,
  tablet two-pane scenes, Settings at 1.5× font scale, French and Japanese.
- **Determinism:** synthetic stored facts replace simulator-generated references, times and card details. The golden
  directory is a declared test input, so a changed PNG reruns the comparison. `scripts/dev check ui-snapshots` compares;
  `scripts/dev snapshots record` rewrites. See the [procedure](../../CONTRIBUTING.md#update-the-golden-screenshots).
- **Approved visual pass:** localized price guidance, one shared tablet header/banner and consistent compact actions
  deliberately update ten existing images, including dialog and picker actions. Five new images cover compact actions
  at both sizes, AMS1 update and cash
  screens, and a whole-number price error. The remaining existing layouts are preserved. The token adoption before
  these visual changes was verified byte-identical across all 54 earlier macOS images.
- **CI rollout:** the separate `ui-snapshots` job uses `ubuntu-latest` and Temurin 21, with a failure artifact
  containing
  `app/build/reports/roborazzi/`. Its manual recording option uploads goldens for review. The initial Linux comparison
  failed on two gallery images; all whole screens passed at 0.1 %. The reviewed goldens were re-recorded by CI run
  `41017912554` on Ubuntu 24.04 image `20261025.353.1`, Temurin `21.0.13+9`. Fresh comparison runs
  [41019777329](https://github.com/astiskala/minimpos/actions/runs/41019777329) and
  [41019780887](https://github.com/astiskala/minimpos/actions/runs/41019780887) both passed all 54 images, with the
  build
  cache disabled. The comparison remains outside `qualityGate` pending owner approval. Independent CI recording runs
  `41017912554` and `41018426929` produced byte-identical PNGs for all 54 images. The owner reviewed that evidence on
  October 30, 2026 and chose to keep the existing thresholds and color-distance filter.
- **Remaining coverage:** checkout review at 1.5×, Japanese results and History's empty state. Scanner already uses
  spacing tokens; its camera hardware is outside these JVM goldens.

## Current state

- UI tests are Compose JUnit tests under Robolectric 4.17 (SDK 33) with native graphics, so rendered pixels are real.
- `createRecordingComposeRule` already writes unmerged trees and root PNGs on **failure** only
  (`app/build/reports/ui-failures/`). `CompactLayoutTest` and `SmallScreenTest` also save previews at AMS1, P630 and
  S1F2
  sizes, and `CompactLayoutTest` exercises 1.5× font scale.
- There are no `@Preview`s. Golden images are the new part: see Status.
- The documentation screenshots (`scripts/docs-capture`) are marketing assets from the AMS1 emulator, not tests.

## Recommendation

Use **Roborazzi** (JVM, on top of the existing Robolectric and Compose test rules), with golden PNGs checked in under
`app/src/test/screenshots/`. Reasons:

- It reuses the current runner, `TestEnvironment`, fakes and device-size helpers; no emulator and no second test stack.
- Paparazzi would need layoutlib and cannot render screens that depend on `AppContainer`/Robolectric fakes.
- Android's official Compose Preview Screenshot Testing needs `@Preview` composables (the app has none, and screens take
  `AppContainer`-backed state), and it is still experimental.

The dependency is `io.github.takahirom.roborazzi` (Gradle plugin + `roborazzi-compose` + `roborazzi-junit-rule`),
test-only. It does not touch release builds or R8. Release 1.77.0 declares no Robolectric or Compose BOM pin and works
with Robolectric 4.17, Compose BOM 2026.09, AGP 9.5, Kotlin 2.4 and Gradle 9.8.

## What to cover

Keep the set focused, not hundreds; there are 59 images so far. Each uses the real theme and shared components with
simulator/fake data.

| Group | Subjects | Variants |
| --- | --- | --- |
| Component gallery (one test, new `DesignGalleryTest`) | Buttons (primary, secondary, tertiary, destructive, loading, disabled), `Card`, `Notice` INFO/WARNING, `SectionHeader`/`Subheading`/`Description`, `BlockedReason`, `ActionMessage`, `StatusBadge` ×3, `EmptyState`, `SwitchRow`, `Keypad`, `OutlinedTextField` with error, `ConfirmDialog` | Regular size, plus Compact for the buttons and keypad |
| Home | Normal, setup required (gated tiles) | AMS1, S1F2, tablet |
| Sale | Product grid, cart with items, custom amount keypad | AMS1, Regular |
| Result screens | Approved, declined, unknown (`OutcomeHeader` + `ResultBar`) | AMS1, Regular |
| Processing | `ProcessingContent` | AMS1 |
| Settings | Main list, one stepped Terminal setup, one field with validation error | Regular, tablet two-pane |
| History | List, detail, empty state | AMS1, tablet two-pane |
| Locale | Home and one result screen | `fr-FR` (Home and the terminal picker done) or `ja` |
| Accessibility | Checkout review, Settings list | 1.5× font scale on AMS1 |

Skip dark mode and dynamic colour: the owner has decided to keep the app light-only.

Device matrix (reuse the sizes already in `SmallScreenTest`): **AMS1** `w320dp-h460dp-hdpi`, **S1F2** 360×640, **Regular
phone** 411×891, **tablet** 1000×1280 for two-pane captures (the expanded-window threshold is 840 dp; 800 dp remains
single-pane). Use at most three per screen.

## Mechanics

1. A `ScreenshotRule` wrapper next to `createRecordingComposeRule` captures the root node with Roborazzi, name derived
   from test class, method and device.
2. Goldens are committed; `verifyRoborazziDebug` compares, `recordRoborazziDebug` rewrites them. They are exposed as
   `scripts/dev check ui-snapshots` (compare) and `scripts/dev snapshots record` (update).
3. Keep rendering deterministic: fixed `Locale`, `TimeZone`, `fontScale`, disabled animations,
  `UnconfinedTestDispatcher`
   per [app/AGENTS.md](../../app/AGENTS.md#tests), `en-rAU` amounts, no live clocks or network, synthetic data only.
4. Tolerance: 0 % changed pixels for the component gallery and 0.1 % for full screens. Roborazzi first ignores color
   distances up to `0.007`, so these are counted pixels, not every raw RGB difference. The first Linux recording
  differed
   from macOS in 50 of 54 images, by at most 2 levels per 8-bit channel, at rasterized edges. This is expected because
   anti-aliasing differs between JVMs and operating systems. Record goldens on one pinned CI image and run comparisons
   there for authoritative results; local comparisons can fail on cross-platform rasterization differences.
5. On mismatch, publish expected/actual/diff images in the existing CI reports artifact (alongside `ui-failures/`).
6. A PR that changes UI deliberately re-records goldens and updates `DESIGN.md` when the visual rule changed.

## Relation to design renders

Android goldens compare approved Android rendering against future Android rendering. Renders from external design
tools show intent only; they are never pixel baselines.

## Rollout

1. Add the plugin and the component gallery test; record the first goldens. Gate by the Gradle `qualityGate` only after
   two stable CI comparison runs and owner approval. (Plugin and gallery done; gate pending.)
2. Add the Home, Sale and result screens on AMS1 and Regular. (Done, except where the table says fewer.)
3. Add locale, large-font and tablet variants. (Representative variants done; remaining coverage above.)

Risks: golden churn when Compose or Material 3 is upgraded (the BOM is bumped by Dependabot), and image size in git
(about 30-60 KB each, roughly 2 MB for 35). Keep image count low and commit PNGs optimized.
