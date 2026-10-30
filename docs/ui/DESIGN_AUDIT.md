# Design audit

Initial audit of the Compose UI against [.stitch/DESIGN.md](../../.stitch/DESIGN.md). Source scan: `scripts/design-audit`
on the commit where this file was added, plus a read of `ui/theme` and `ui/components`. No visual change was made as a
result of this audit; the only code edits were the behaviour-neutral token adoption listed under *Changes made*.

**Overall finding.** The UI is already close to a design system. The app (219 Kotlin files) uses Material 3 only
(ArchUnit), there are no colour literals or `RoundedCornerShape`s outside `ui/theme`, shared text roles and buttons
exist and are enforced by ArchUnit, and `Dimens` handles three screen tiers. The remaining debt is spacing and
icon-size literals, a few raw Material buttons, and two duplicated picker dialogs. There is no confirmed accidental
colour or typography deviation.

## Scan results

| Check | Count | Level |
| --- | --- | --- |
| `Color(0x…)` outside `ui/theme` | 0 | Hard rule, clean |
| `RoundedCornerShape`/`CutCornerShape` outside `ui/theme` | 0 | Hard rule, clean |
| `MaterialTheme.shapes.*` uses | 22 (medium 14, large 5, extraLarge 2, small 1) | Informational |
| `sp` literals outside theme | 5 | Soft |
| Raw `Button`/`OutlinedButton`/`TextButton` in feature code (excluding dialog slots) | 21 | Soft |
| `dp` literals in feature, scan and activity code | 129 | Soft |
| `dp` literals in `ui/components` after token adoption | 27 | Soft |
| Elevation literals | 0 (was 2) | Soft |
| `@Preview` composables | 0 | Gap |
| Dark theme / dynamic colour | none, by design | Documented |

Most used `dp` literals: 8 (45), 12 (45), 16 (25), 24 (22), 4 (17), then 48, 0, 56, 20, 2. The 4/8/12/16/24/32 scale is
the canonical one and is now `Spacing`.

## Findings

Confidence is for the finding being a real deviation from the proposed spec. *Safe to auto-fix* means a mechanical,
value-preserving change with no visual or behavioural difference, verifiable by the existing tests.

### TOKEN_VIOLATION

| # | File / component | Current | Expected | Confidence | Recommended change | Auto-fix |
| --- | --- | --- | --- | --- | --- | --- |
| T1 | `ui/components/Components.kt`, `Buttons.kt`, `TextRoles.kt`, `Outcomes.kt` | Literal 4/8/12/16/24/32 dp, 48 dp, 8 dp shadow | `Spacing`, `MinTouchTarget`, `Elevation.ActionBar` | High | **Done** for equal-value cases | Yes |
| T2 | `ui/components/Steps.kt`, `Widgets.kt`, `Dialogs.kt` | Literal 4/12/24 spacers, 16/24 icons, 40 badge | `Spacing`, new `IconSize` steps | High | Adopt tokens; add `IconSize.Standard` (24), `Tile` (32) when used | Yes |
| T3 | Feature screens (129 `dp` literals; top: `SaleScreen` 10, `TerminalSetupSteps` 7, `CheckoutScreens` 5, `HomeScreen` 5) | Literal 4/8/12/16/24 | `Spacing.*` or `Dimens.*` | High where the value is on the scale and plays a spacing role | Replace per file with tests; leave widths and one-off sizes | Yes, per file |
| T4 | `feature/sale/SaleScreen.kt` | Cart pane 340 dp, tile grid 132 dp, cart list 420 dp | Named layout constants | Medium | Name them next to their use (some already are) | No (layout intent) |
| T5 | `scan/Scanner.kt`, `MainActivity.kt` | 24 dp padding, 3 dp frame border, 400 dp max width | `Spacing.XLarge` for the padding; border stays | Medium | Adopt `Spacing` only | Padding only |

### SPACING_INCONSISTENCY

| # | File / component | Current | Expected | Confidence | Recommended change | Auto-fix |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | `SectionHeader` (`TextRoles.kt`) | Top 12 dp compact, **20 dp** regular | Scale step 16 or 24 | Medium | **Done:** 20 dp is the deliberate `Spacing.Section` step (also the picker padding) | No |
| S2 | `Components.kt` `LabeledValue` | Vertical 3 dp compact, 4 dp regular; the simulator banner in `Widgets.kt` uses 2/4 dp | One of 2/4 | Low | Keep; density tuning for AMS1 | No |
| S3 | `Steps.kt` | Top 16/24, bottom 4/8 | Scale values | High | Adopt tokens only | Yes |
| S4 | `CapabilitiesSettings.kt:276`, `SettingsComponents.kt:331` | Vertical **10 dp** | `Dimens.rowPadding` (12/10/8) | Medium | **Kept:** listed in `DESIGN.md` › Known Exceptions | No |
| S5 | `Dimens.Medium.tileVerticalPadding` | **6.25 dp**; Regular 6; Compact 5 | 6 | Low | Fractional value looks tuned to fit AMS1/S1F2 two-line tiles; leave | No |
| S6 | Picker dialogs (`CurrencySetting.kt`, `TerminalSetupSteps.kt`) | 24 dp horizontal, 12/20 vertical, `heightIn(max = 600)` | One shared definition | High | **Done** with C1 | No |

### COMPONENT_DUPLICATION

| # | File / component | Current | Expected | Confidence | Recommended change | Auto-fix |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | `CurrencySetting.kt` and `TerminalSetupSteps.kt` (~lines 140-205, 750-840) | Two near-identical searchable picker dialogs (padding, 600 dp max height, `Search`, cancel `TextButton`) | One `PickerDialog` in `ui/components` | High that they duplicate; medium on the right API | **Done:** `PickerDialog`; the currency and terminal picker goldens match before and after | No |
| C2 | Dialog cancel `TextButton`s (`TaxSettings`, `ProductsScreens`, `CheckoutScreens`, `SettingsComponents`) | Same cancel/delete slot repeated | Keep; dialog slots are idiomatic | Medium | Optional `DialogCancelButton` helper | No |
| C3 | `WalletChoice.kt:52`, `TransferScreens.kt:508` | `OutlinedButton` with custom content, bypassing `SecondaryButton` | `SecondaryButton` with a content slot | Medium | Needs a content-slot overload; behavioural risk on tap target | No |
| C4 | `HomeScreen.kt:244` `Button` | Compact "Download" button in the update banner (not a payment tile) | `PrimaryButton` is full width, so a compact variant would be needed | Medium | Leave; revisit if more compact buttons appear | No |
| C5 | 22 `OutlinedTextField` call sites | Each configures labels, keyboard and errors itself | Optional `AppTextField` | Low | Wait: settings fields use a title/description/input pattern that may not need a wrapper | No |

### SHAPE_INCONSISTENCY

None confirmed. `medium` (12 dp) is the dominant shape (14 uses) with `large` (16 dp) reserved for Home tiles, the
custom-amount dialog and the scanner frame. `small` (8 dp) appears once (receipt preview). `shapes.extraLarge` (2 uses)
and `extraSmall` (0) are in the theme but barely used; keep them as Material completeness.

### TYPOGRAPHY_INCONSISTENCY

| # | File / component | Current | Expected | Confidence | Recommended change | Auto-fix |
| --- | --- | --- | --- | --- | --- | --- |
| Y1 | `SaleScreen.kt:542` | `titleSmall.copy(lineHeight = 18.sp)` | Role style | Medium | Tuned to fit two lines in a tile; keep, document | No |
| Y2 | `SplitScreens.kt:499,509`, `HomeScreen.kt:482,485` | `12.sp`/`16.sp` autosize bounds | Named constants | High | Home already names them; name the split ones | Yes |
| Y3 | `labelLarge` fixed at 16 sp (theme) | Differs from Material's 14 sp | Intentional | High | Document (done) | n/a |

### COLOUR_INCONSISTENCY

None confirmed. `onSurfaceVariant` (41 uses) is the secondary text colour everywhere; `error` and `LocalStatusColors` are
used for outcomes. `Color.White` (15) and `Color.Black` (3) are in the scanner overlay and print/receipt rendering, which
are not themed UI. `tertiary` (blue) has no feature-level use besides links. `StatusColors` is a hand-built provider
because Material lacks success and warning slots: expected.

### ACCESSIBILITY

| # | File / component | Current | Expected | Confidence | Recommended change | Auto-fix |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | `Components.kt` `BlockedReason` icon 18 dp; Buttons spinners 18/22 dp | Off-grid sizes | 16/20/24 | Medium | Not an accessibility defect (decorative); pick sizes in a design pass | No |
| A2 | `QuantityStepper` | `IconButton` default 48 dp | 48 dp | High | Already compliant | n/a |
| A3 | Touch-target rule | No automated check | Add a test or lint | Medium | See enforcement plan | No |
| A4 | Contrast | `ThemeContrastTest` covers the scheme | Add pairs for any new token | High | Keep | n/a |
| A5 | Dark mode | None | Product decision | n/a | See R1 | No |

### LIKELY_INTENTIONAL_EXCEPTION

- Compact-only `RectangleShape` full-screen custom-amount dialog (`SaleScreen.kt:672`).
- `Dimens` per-tier fractions (6.25 dp tile padding, 10 dp medium spacing) tuned to Adyen terminal sizes.
- `screenPadding + 8.dp` on Home payment tiles; `fabClearance` 88 dp; 5 dp progress stroke.
- Light-only theme and flat elevation (no TEST banner, no status line: AGENTS.md).
- `Color.White`/`Black` in scanner and receipt rendering.

### REQUIRES_DESIGN_DECISION

| # | Question | Why it is open |
| --- | --- | --- |
| R1 | Dark theme for phones and tablets | **Decided:** owner keeps light-only; no dark mode |
| R2 | The 20 dp spacing and icon step | **Decided:** `Spacing.Section` (spacing) and `IconSize.Inline` (icon) |
| R3 | 18 dp icons and spinners | **Decided:** kept as a documented exception |
| R4 | Whether raw `TextButton`s in picker rows are intended | They are list rows styled as text buttons |
| R5 | `AppTextField`, `AppDialog`, `AppTopBar` wrappers | Current `MiniScaffold` and dialog family already cover most uses; wrappers need a concrete duplication to justify them |

## Changes made

Behaviour-neutral only; values are identical before and after.

- Added `ui/theme/Tokens.kt`: `Spacing`, `IconSize.Inline`, `MinTouchTarget`, `Elevation.ActionBar`.
- Adopted them in `Buttons.kt`, `Components.kt`, `TextRoles.kt`, `Outcomes.kt` and the sale screen's cart bar.
- Added `scripts/design-audit` (read-only report; `--strict` gates colour and shape literals).
- After the screenshot baseline: adopted `Spacing` for the equal-value 4/8/12/16/24/32 dp spacing literals in shared
  components and in the feature screens it touched, and `IconSize.Small`, `Standard` and `Large` (16, 24, 40 dp). The
  remaining equal-value spacing literals in PIN entry, Transfer, Reconciliation, Troubleshooting, unreferenced refunds,
  Products and receipt import were then adopted after adding goldens per file. All 54 macOS goldens remained
  byte-identical. Scanner already used spacing tokens; SaleScreen retains its layout widths and grid-cell size.
  Dp literals went from 129/27 to the counts `scripts/design-audit` prints.
- Extracted `PickerDialog` for the currency and terminal pickers (C1, S6); the picker goldens match exactly.
- Reviewed the raw Material buttons (T-class, soft): none was migrated. Each is a dialog action slot, a row or chip
  with its own size (update banner, play/pause, wallet row, API-roles expander, list-row choices), none the full-width
  Primary/Secondary/Tertiary shape, so a migration would change how it looks. They are listed in `DESIGN.md` ›
  Known Exceptions.

## Not changed

Everything marked *No* above and not listed under *Changes made*: any 20 dp / 18 dp / 10 dp normalisation, raw-button
migration, dark mode and every medium or low confidence item.

## Recommended order

1. ~~Capture screenshot baselines~~ ([SCREENSHOT_TESTING.md](SCREENSHOT_TESTING.md)): done; the goldens cover
   components, Home, the sale keypad, the results, Settings and the pickers.
2. ~~Finish T2 and T3 file by file~~ for equal-value literals in covered screens (done); the rest, the 6, 10 and 20 dp
   and one-off sizes remain.
3. ~~Decide R2, R3 and S1~~ (done: `Spacing.Section`; 18 dp and 10 dp kept as exceptions).
4. ~~Extract the picker dialog (C1).~~ Done.
5. Gate `scripts/design-audit --strict` in CI.
