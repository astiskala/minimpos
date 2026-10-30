# Screenshot testing strategy

The baseline is implemented as described in [Status](#status); the rest of this page is still the plan. It extends what
the repository already does.

## Status

- **Done:** Roborazzi 1.77.0 (plugin, `roborazzi`, `roborazzi-compose` and `roborazzi-junit-rule`, test-only, through the
  version catalog; release builds and R8 are unaffected). `ScreenshotRule` sits beside `createRecordingComposeRule`.
  `DesignGalleryTest` has the component gallery (12 images, compared exactly, buttons and keypad also at the S1F2's Medium
  dimensions) and `ScreenGoldensTest` has Home (normal on AMS1 and a regular phone, setup required on AMS1), the sale
  keypad and the product grid with two items in the cart (both sizes), History with three stored sales (both sizes) and
  the approved (both sizes), declined and unknown results (AMS1): 13 screens at 0.1 %. The results
  come from simulator payments made in the app,
  with the reference, time, card and Adyen references the simulator makes up replaced by fixed values in the stored
  sale. `SettingsGoldensTest` adds the Settings list, the Terminal setup, a price field in error and the currency and
  terminal picker dialogs (AMS1 and regular phone): 7 more at 0.1 %. The golden directory is a declared input of the
  test task, so a changed golden always reruns the comparison, never a cached result. Goldens are in `app/src/test/screenshots/`.
  `scripts/dev check ui-snapshots` compares and `scripts/dev snapshots record` rewrites them; the procedure is in
  [CONTRIBUTING](../../CONTRIBUTING.md#update-the-golden-screenshots).
- **Not done:** the other groups below (processing, History detail, locale, large font, tablet), screens at the
  S1F2 size, the
  pinned CI image, the comparison in `qualityGate`, and a CI step that publishes the difference images on their own.

## Current state

- UI tests are Compose JUnit tests under Robolectric 4.17 (SDK 33) with native graphics, so rendered pixels are real.
- `createRecordingComposeRule` already writes unmerged trees and root PNGs on **failure** only
  (`app/build/reports/ui-failures/`). `CompactLayoutTest` and `SmallScreenTest` also save previews at AMS1, P630 and S1F2
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

Aim for about 35 images, not hundreds; there are 32 so far. Each uses the real theme and shared components with
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
| Locale | Home and one result screen | `fr-FR` or `ja` (long and CJK text) |
| Accessibility | Checkout review, Settings list | 1.5× font scale on AMS1 |

Skip dark mode (unsupported by design) and dynamic colour until the product decision R1 in the audit is made.

Device matrix (reuse the sizes already in `SmallScreenTest`): **AMS1** `w320dp-h460dp-hdpi`, **S1F2** 360×640, **Regular
phone** 411×891, **tablet** 800×1280. Use at most three per screen.

## Mechanics

1. A `ScreenshotRule` wrapper next to `createRecordingComposeRule` captures the root node with Roborazzi, name derived
   from test class, method and device.
2. Goldens are committed; `verifyRoborazziDebug` compares, `recordRoborazziDebug` rewrites them. They are exposed as
   `scripts/dev check ui-snapshots` (compare) and `scripts/dev snapshots record` (update).
3. Keep rendering deterministic: fixed `Locale`, `TimeZone`, `fontScale`, disabled animations, `UnconfinedTestDispatcher`
   per [app/AGENTS.md](../../app/AGENTS.md#tests), `en-rAU` amounts, no live clocks or network, synthetic data only.
4. Tolerance: 0 % for the component gallery, a small per-image threshold (about 0.1 %) for full screens, because
   anti-aliasing differs between JVMs and operating systems. Record goldens on one pinned CI image and run comparisons
   there only; local runs report but do not fail.
5. On mismatch, publish expected/actual/diff images in the existing CI reports artifact (alongside `ui-failures/`).
6. A PR that changes UI deliberately re-records goldens and updates `.stitch/DESIGN.md` when the visual rule changed.

## Relation to Stitch

Android goldens compare approved Android rendering against future Android rendering. Stitch output defines intent and
lives under `.stitch/reference/` for human comparison; it is never a pixel baseline.

## Rollout

1. Add the plugin and the component gallery test; record the first goldens. Gate by the Gradle `qualityGate` only after
   two stable CI runs. (Done.)
2. Add the Home, Sale and result screens on AMS1 and Regular. (Done, except where the table says fewer.)
3. Add locale, large-font and tablet variants.
4. Use the goldens as the safety net for the token cleanup and the picker-dialog extraction listed in
   [DESIGN_AUDIT.md](DESIGN_AUDIT.md).

Risks: golden churn when Compose or Material 3 is upgraded (the BOM is bumped by Dependabot), and image size in git
(about 30-60 KB each, roughly 2 MB for 35). Keep image count low and commit PNGs optimized.
