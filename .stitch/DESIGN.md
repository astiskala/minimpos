# Mini mPOS Design System

First-pass specification derived from the shipped Compose UI. It is the visual reference for coding agents and Google
Stitch. Values marked **Observed** come from source and are the implementation today; **Inferred** marks a rule the code
suggests but does not enforce; **Decision needed** marks a point the code leaves open. Where this file and the code
disagree, see [docs/ui/DESIGN_AUDIT.md](../docs/ui/DESIGN_AUDIT.md) before changing either.

Source of truth in code: [Theme.kt](../app/src/main/java/app/minimpos/app/ui/theme/Theme.kt) (colours, typography,
shapes, `Dimens`), [Tokens.kt](../app/src/main/java/app/minimpos/app/ui/theme/Tokens.kt) (spacing, icon size, touch
target, elevation) and the shared components in
[ui/components/](../app/src/main/java/app/minimpos/app/ui/components/). Behaviour and layout rules that are not
visual live in [app/AGENTS.md](../app/AGENTS.md) and are not repeated here.

## Design Principles

All **Observed** in code comments, tests or AGENTS.md; none are invented here.

1. **Terminal-first.** The palette follows the Adyen terminal payment UI: white surfaces, green actions, dark navy
   text (`AdyenColors`). The smallest screen is the AMS1, about 320×460 dp; its primary action must be visible without
   scrolling (`SmallScreenTest`).
2. **One main action per screen.** The primary action is pinned in `BottomActions`. Workflow screens use one pinned
   "Verb amount" button; result screens put Home beside the primary action.
3. **Tell operators what to do.** A disabled action shows why (`BlockedReason`); explanations use `Description`;
   requirements and risks use `Notice`. Do not invent a new text role.
4. **Colour carries outcome, never decoration.** Green is success, primary action, links and tertiary actions; orange
   is unknown or needs attention; red is declined or destructive. Feature icons on Home tiles and Settings rows are
   green as the one deliberate identity exception. Values, such as a setting's current choice, stay grey.
5. **Light only, high contrast.** Every text pair meets WCAG AA 4.5:1 and outlines or meaningful icons 3:1
   (`ThemeContrastTest`).
6. **Density follows the screen.** `Dimens` has three size sets (Compact, Medium, Regular); compact screens drop
   decoration instead of shrinking tap targets.
7. **Deliberate UI choices stay.** No TEST banner (the simulator banner only), no Home terminal-status line, two
   full-width payment tiles on Home. See [AGENTS.md](../AGENTS.md#payment-and-product-boundaries).

## Colour

Material 3 `lightColorScheme` built from `AdyenColors` in `Theme.kt`. There is no dark scheme and no dynamic colour
(see [Dark Mode](#dark-mode)). Feature code must read colours from `MaterialTheme.colorScheme` or `LocalStatusColors`,
never from `AdyenColors` or `Color(0x…)`.

### Surfaces

| Role | Value | Used for |
| --- | --- | --- |
| `background`, `surface`, `surfaceContainerLow` | `#FFFFFF` | Screens, app bar |
| `surfaceContainerHigh` | `#FFFFFF` | Dialogs (`AlertDialog` family, `PickerDialog`), so cards and notices inside keep their contrast |
| `surfaceVariant`, `surfaceContainer`, `secondaryContainer` | `#F3F6F9` (`GreyLight`) | `Card`, product tiles, info `Notice`, keypad keys, menus |
| `surfaceContainerHighest`, `outlineVariant` | `#E3E8EE` (`Border`) | Dividers, `ActionGroup` border |
| `primaryContainer` | `#E6F8EE` (`GreenLight`) | A product already in the cart (with a 1 dp primary outline) |
| `errorContainer` | `#FBE6ED` | Error panels |
| `StatusColors.warningContainer` | `#FFF1E5` | Warning `Notice`, simulator banner |

### Content

| Role | Value | Used for |
| --- | --- | --- |
| `onSurface`, `onBackground` | `#00112C` (`Navy`) | Body and title text |
| `onSurfaceVariant` | `#5C687C` (`Grey`) | Secondary text; the most used colour role after `onSurface` |
| `outline` | `#848FA0` | Field, secondary-button and chip outlines (3:1 on white) |
| `onPrimary`, `onError`, `StatusColors.onStatus` | `#FFFFFF` | Content on green, red and status badges |

### Brand

| Role | Value | Note |
| --- | --- | --- |
| `primary` | `#00843F` (`Green`) | Deepened from Adyen's `#0ABF53` (2.4:1 with white) to pass AA with white text |
| `secondary` | `#00112C` (`Navy`) | Secondary actions: their label and icon use `onSurface`, the same navy |

`tertiary` is left at Material's default and unused: links and tertiary actions are green.

The launcher background is the same green (`res/values/colors.xml`).

### Semantic colours

`LocalStatusColors` supplies what Material has no slots for. `StatusKind` maps outcome to colour and glyph.

| Meaning | Colour | Glyph | Use |
| --- | --- | --- | --- |
| Success | `#00843F` | Tick | Approved, completed, `ActionMessage` |
| Warning | `#B35300` on `#FFF1E5` | Exclamation | Unknown, pending, needs attention, `BlockedReason` icon |
| Error | `#D10244` on `#FBE6ED` | Cross | Declined, failed, destructive actions (`ConfirmedRemoval`, destructive `TertiaryButton`) |

Do not signal status by colour alone: the glyph (`StatusKind.icon()`) and the text always accompany it, on result
badges and beside a list row's status. Invalid text fields add Material's error icon (`fieldErrorIcon`) to the red
outline and the supporting text.

## Typography

Material 3 default type scale with a few weight overrides (`typography` in `Theme.kt`). The font is the system default;
there is no custom font.

| Role | Override | Observed use |
| --- | --- | --- |
| `displayMedium` | Bold, −0.5 sp tracking | Base of `AmountTextStyle` |
| `AmountTextStyle` | 44 sp (Medium 38, Compact 34), −1 sp tracking, Bold | Amounts on checkout, keypad and results |
| `headlineMedium` | Bold | Regular-size outcome title ("Approved") |
| `headlineSmall` | SemiBold | Compact outcome title; keypad digits |
| `titleLarge` | SemiBold | Payment tile titles; regular app bar title |
| `titleMedium` | SemiBold | Compact app bar title; quantity |
| `titleSmall` | default | `Subheading` (block, step, card title); product tile name |
| `labelLarge` | SemiBold, 16 sp | All button labels |
| `labelMedium` | default | `SectionHeader` (upper case) |
| `bodyLarge`, `bodyMedium` | default | Row text, `ActionMessage`, `BlockedReason` |
| `bodySmall` | default | Explanations on compact screens; secondary lines |

Rules:

- Text roles are fixed: `SectionHeader` (upper case, groups rows or fields), `Subheading` (sentence case, a block),
  `SheetTitle`, `Description`, `Notice`, `BlockedReason`, `ActionMessage`, `OutcomeNote`. Only `ui.components` marks
  headings (ArchUnit).
- Explanations are one size smaller on compact screens (`explanationStyle`).
- Money is always `Long` minor units formatted by `MoneyFormatter`; amounts use `AmountTextStyle`, other money uses
  the surrounding body role.
- Text must survive 1.5× font scale on the smallest screens (`CompactLayoutTest`). Prefer `TextAutoSize` and wrapping
  to truncation for titles (Home payment tiles).

## Spacing

Two layers, both **Observed**.

**Screen-dependent (`Dimens`, chosen by `Dimens.forWindow`)**

| Token | Regular | Medium | Compact |
| --- | --- | --- | --- |
| Window rule | height ≥ 640 dp | height 520–639 dp | width < 360 dp or height < 520 dp |
| `screenPadding` | 16 | 16 | 12 |
| `spacing` | 12 | 10 | 8 |
| `rowPadding` | 12 | 10 | 8 |
| `cardPadding` | 16 | 14 | 12 |
| `rowHorizontalPadding` | 16 | 16 | 16 |
| `keySpacing` | 8 | 8 | 6 |

Widths: `contentMaxWidth` 560, `listMaxWidth` 640, `narrowActionMaxWidth` 320, `homeMaxHeight` 640, `fabClearance` 88.

**Fixed scale (`Spacing`, `Tokens.kt`)**: 4 `XSmall`, 8 `Small`, 12 `Medium`, 16 `Large`, 20 `Section`, 24 `XLarge`,
32 `XXLarge`. Use it for gaps that do not depend on screen size. `Section` is the deliberate half-step above a regular
screen's section header and around picker dialogs; the 20 dp icon is `IconSize.Inline`.

**Screen margin.** Every screen's content, including the sale screen's search, category chips, Custom item button and
product grid, starts `screenPadding` (16/16/12 dp) from the edge; gaps between items use `spacing`.

**Outline width.** `BorderWidth` (1 dp) for secondary buttons, chips, the `ActionGroup` border and products in the
cart.

## Shapes

`MaterialTheme.shapes` (**Observed**): extraSmall 6, small 8, medium 12, large 16, extraLarge 24 dp.

| Shape | Used for |
| --- | --- |
| `medium` (12) | **Canonical.** Buttons, keypad keys, `Card`, `Notice` and product tiles |
| `large` (16) | Home payment tiles, custom-amount dialog, scanner frame |
| `small` (8) | Receipt preview border |
| `extraLarge` (24) | Dialog surfaces: the `AlertDialog` family and `PickerDialog` |
| `CircleShape` | Status badge, step number, PIN dots |
| `RectangleShape` | Custom-amount dialog on compact screens (full-screen takeover) |

Never construct `RoundedCornerShape` in feature code; none exists today.

## Elevation

The UI is flat. Only the pinned action bar casts a shadow: `Elevation.ActionBar` = 8 dp (`BottomActions`, sale
screen's cart bar). Separation otherwise comes from `HorizontalDivider` (15 uses), the `Border` colour and tonal
surfaces. **Decision needed:** whether dialogs and sheets should use Material defaults indefinitely.

## Icons

- Material icons (`Icons.Default`, `Outlined`, `AutoMirrored`); icons are decorative (`contentDescription = null`)
  whenever text sits beside them.
- Observed sizes (dp): 16 compact notice and finished step (`IconSize.Small`), 18 blocked reason, **20 beside button
  labels (`IconSize.Inline`)**, 24 rows and steps (`IconSize.Standard`), 32 payment tiles
  (`IconSize.Tile`), 40 empty state and PIN lock
  (`IconSize.Large`; 56 on regular empty state). 18 is the one off-grid size.
- Status badge glyph is 55% of the badge (88/64/56 dp by size set).
- The application icon is PNG, not adaptive; `MainActivity` has its own adaptive icon (AGENTS.md).

## Components

All in `ui/components/` unless noted. Prefer these over raw Material components in feature code.

### Buttons

| Component | Material base | Height | Notes |
| --- | --- | --- | --- |
| `PrimaryButton` | `Button` | `Dimens.buttonHeight` 56/52/48 | Full width, `medium` shape, optional icon, spinner while `loading` |
| `SecondaryButton` | `OutlinedButton` | `secondaryButtonHeight` 52/48/44 | Same layout; navy (`onSurface`) label and icon, 1 dp `outline` border (disabled: `onSurface` at 12 %) |
| `TertiaryButton` | `TextButton` | `secondaryButtonHeight` | `destructive` uses the error colour |
| `CompactSecondaryButton` | `OutlinedButton` | At least 48 dp | Natural width, `medium` shape, `labelLarge`, the same navy label and border; update action, transfer play/pause and inline Exact cash |
| `ActionGroup` | clickable rows | At least 48 dp per action | Related secondary actions (receipt actions) as one bordered list; see below |
| `ChoiceChip` | `FilterChip` | 32 dp drawn, 48 dp touch | Filters and single choices: `outline` border; selected adds a tick, `primaryContainer` fill and `primary` border |
| `CompactTertiaryButton` | `TextButton` | At least 48 dp | Natural width, same shape and label role; dialog actions. Not for a lone inline action beside a label, which reads as text: use `CompactSecondaryButton` |
| `GatedButton` (`feature/`) | wraps the above | | Use for any action reaching Adyen or a terminal |
| `QuantityStepper` | `IconButton` | 48 dp target | − qty +, names the item for screen readers |

Labels use `labelLarge`; the icon is 20 dp with an 8 dp gap; the loading spinner is the same 20 dp and 8 dp gap,
so a button's label does not move while it loads. The three levels read in order: green fill, navy outline, green
text.

`ActionGroup` keeps its actions in order, as one white group at every screen size. It has a 1 dp `outlineVariant`
border and `medium` shape, with rows of `secondaryButtonHeight` (at least 48 dp): a 20 dp navy icon, a 12 dp gap,
then the navy `labelLarge` label. Inset dividers start at the labels. Rows have no chevron because they act rather
than navigate. Actions never sit side by side, even on the AMS1: the owner found two-column receipt buttons too
crowded. `ReceiptActions` uses it for print, merchant copy, email, share and show/hide receipt.
Feature buttons use shared frames, enforced by `ButtonArchitectureTest`. Custom content slots retain wallet artwork,
cart counts and row layouts while sharing shape and touch sizing.
Removal of saved keys, PINs, registrations or history uses red `ConfirmedRemoval`.

### Text fields

`OutlinedTextField` (22 uses) with the default Material 3 outline using the `outline` colour. A field that can be
invalid passes `trailingIcon = fieldErrorIcon(isError)`, unless its trailing slot holds an action such as the SKU
scanner. Settings fields use an
external title (same style as toggles), then a primary description, then the input; notes below hold only extra
information or validation (app/AGENTS.md). No custom field component exists. **Decision needed:** whether to extract
`AppTextField` (see audit).

Invalid product prices show localized supporting text: positive values and the currency's precision, whole numbers for
zero-decimal currencies, or a smaller-price instruction on minor-unit overflow. Accepted price syntax is unchanged.

### Cards

`Card` (not Material's): full width, `surfaceVariant`, `medium` shape, `cardPadding`, no border or elevation. Selected
product tiles use `primaryContainer` with a 1 dp primary border. `Notice` is the tinted-panel variant.

### Dialogs

`ConfirmDialog`, `ResultDialog`, `InfoDialog`, `TextInputDialog`, `ActionOptionsDialog`, `EmailReceiptDialog` and
`SharedKeyDialog` wrap Material `AlertDialog` (12 uses). Result dialogs show a 40 dp `StatusBadge`. Dialog buttons are
shared `CompactTertiaryButton` slots, retaining destructive and disabled states. Settings › Terminal reports setup
outcomes in a dialog, one at a time.
Dialog surfaces use `shapes.extraLarge` and `surfaceContainerHigh`, which is white, so inner cards and notices keep
their grey; inner cards retain `shapes.medium`.
Dialogs containing text fields use `DialogProperties(usePlatformDefaultWidth = false)` in tests.

### Navigation

`MiniScaffold`: `CenterAlignedTopAppBar` on `surface`, height `Dimens.topBarHeight` 64/56/48, back arrow when
`onBack != null`, `ModeBanner` (simulator only) and a divider under the bar, content above the keyboard.
Navigation 3 transitions are owned by `AppNavHost`. Home and onboarding are roots. Expanded windows show History and
Settings details beside the list. At the expanded-width breakpoint (840 dp), an active list/detail scene uses one
`NavigationFrame` app bar and simulator banner. `MiniScaffold` list panes retain actions; detail panes show a semantic
heading in `Dimens.titleStyle`. Explicit `ScreenHeader` parameters keep presentation separate from access gates.
The shared Back arrow leaves the section. System Back retains Navigation 3's and each screen's existing behavior;
expanded Settings exits the scene, while phone Settings closes its detail first.

### Lists and rows

`SwitchRow` (min 48 dp, toggleable as one target), `LabeledValue`, `TransactionRow`, `SettingActions`, `ListItem`
(2 uses). Rows use `rowPadding` vertical and `rowHorizontalPadding` horizontal. Section titles are `SectionHeader`.
A disabled `SwitchRow` fades its title and description together (`onSurface` at 38 %). A `TransactionRow` shows its
status as a 16 dp glyph and the word in the status colour. A choice setting shows its current value in
`onSurfaceVariant`, like a navigation row. A saved secret's "Set" and "Not set" stay green and red because they are
states.

### Keypad and amounts

`Keypad` / `NumericKeypad`: `FilledTonalButton` keys, `medium` shape, `keyHeight` 56/52/44, `keySpacing` gap, digits
in `headlineSmall`. Compact screens use the full-screen keypad so the primary action stays in view. PIN entry uses 16 dp
dots.

### Status, empty and loading states

- `OutcomeHeader` + `StatusBadge` + `OutcomeNote`, then `ResultBar` (Home + next step). With room the badge
  (`badgeSize` 88 dp) sits centred above the outcome and amount. On height-limited screens (`Dimens.compact`) a 40 dp
  badge (`IconSize.Large`) sits beside a start-aligned outcome over amount, centred as a block, so result and detail
  screens keep their receipt actions above the pinned bar on the AMS1.
- `ProcessingContent`: `CircularProgressIndicator` 64/56/48 dp, 5 dp stroke, while the terminal works.
- `EmptyState`: centred 56 dp icon (40 compact), title, description, optional action.
- `ActionMessage` (success/error) and `BlockedReason` give inline feedback; screen readers announce both.
- Stepped screens: `StepHeader`/`StepBody`, later steps hidden until unlocked, a tick once done.

### Home

Two full-width green payment tiles (`large` shape, `titleLarge` with shared autosize) then Tokens/Gift cards,
Refund/Cashout and History/Reconciliation, row-major. Settings is the top-right icon under the admin lock. Gated tiles
stay pressable and explain.

## Layout

- Edge to edge with transparent system bars; the theme measures the area between them (`MiniMposTheme`).
- Content columns cap at `contentMaxWidth` 560 dp (lists 640 dp) and centre on tablets; History and Settings go
  two-pane in expanded windows via Material 3 adaptive navigation.
- Reference sizes: AMS1 320×460 dp (Compact), P630, S1F2 360×640 (Medium), S1F4 Pro and phones (Regular).
- Primary actions live in `BottomActions` (`MiniScaffold.bottomBar`); the bar hides while typing.

## Accessibility

**Observed:** WCAG AA contrast tests; headings via `semantics { heading() }` only in `ui.components`; polite live
regions on `BlockedReason` and `ActionMessage`; merged semantics on `Notice` and `SwitchRow`; item names on stepper
buttons; `stateDescription` on cart products; audio guidance for payments; large-text tests at 1.5×.

**Rules:** tap targets at least `MinTouchTarget` 48 dp (rows, icon buttons); never convey status by colour alone;
announce state changes; new text uses string resources in all seven languages. Evaluate hit areas with effective
`touchBoundsInRoot`, not only drawn bounds: Compact buttons and keypad keys may draw at 44 dp with a 48 dp touch area.
`DesignGalleryTest` checks enabled clickable targets in each gallery capture in both axes.

## Dark Mode

Not supported by design: `MiniMposColors` is `lightColorScheme`, the XML window theme is
`Theme.Material.Light.NoActionBar` with light status/navigation bars, and `window_background` is white. No dynamic
colour.
This follows the terminals' own payment screens. The owner has decided to keep light-only on phones and tablets too.

## Known Exceptions

| Exception | Where | Why |
| --- | --- | --- |
| `RectangleShape` full-screen dialog | `SaleScreen` custom amount, compact only | Keypad takes the whole screen |
| `Color.White`/`Black` | Scanner and receipt rendering | Camera overlay and print preview are not themed UI |
| 5 `sp` literals | Home tile autosize bounds, split-payment autosize, product tile line height | Autosize needs concrete bounds |
| 10 dp row padding | `CapabilitiesSettings`, `SettingsComponents` list rows | Kept as is: moving to `rowPadding` would change these rows' height |
| 6/10/18 dp and one-off layout sizes; 340/420 dp layout widths | Feature layouts and `Dimens` | Deliberate density tuning and layout constraints; token adoption preserves these values |
| Simulator banner | `ModeBanner`, hidden during first-run setup | The only mode indicator by design |
| No Home terminal-status line, no TEST banner | Home | Deliberate product choices |

## Revision — October 31, 2026

**Status: implemented** on the owner's approval of every item. The rules above include them. Explored in Stitch project
`14709404705320367655` (see [README](README.md)); reference renders are in `reference/stitch-review-2026-10-31/`.
Stitch renders show intent only. They substitute Roboto Flex, regenerate some Material roles and invent icons, so
never copy their values or icons.

### Confirmed defects (code or spec contradicts itself)

| # | Finding | Evidence | Proposal |
| --- | --- | --- | --- |
| P1 | `SecondaryButton` reads weaker than `TertiaryButton`: grey `onSurfaceVariant` label and a `#E3E8EE` border (about 1.2:1 on white) against a green text-only tertiary. Neither colour was chosen; both are Material 3 `OutlinedButton` defaults. | `Buttons.kt` passes no colours or border; gallery and result goldens | Secondary label and icon `onSurface` (navy, the documented `secondary` role); border 1 dp `outline` (`#848FA0`, 3.3:1). Applies to the compact variant too. |
| P2 | Dialog surface is specified twice, differently: white in *Surfaces*, `surfaceContainerHigh` (`#F3F6F9`) in *Dialogs*. A `Card` inside a dialog has the same colour as the dialog, so it disappears. | `Dialogs.kt` `AlertDialog` uses the default container; confirm-dialog golden | White dialogs, so inner cards and notices keep their tonal contrast: set once as the scheme's `surfaceContainerHigh`, which only dialogs read. |
| P3 | A disabled `SwitchRow` dims its title to 38 % but leaves its description at full `onSurfaceVariant`. | `Components.kt` `SwitchRow`; tablet Settings golden | Dim the description with the title. |
| P4 | Blue (`tertiary`, "links only") has no use anywhere; links and tertiary actions are green. | No `colorScheme.tertiary` reference in `app/src/main` | Document green as the link and tertiary-action colour; remove the blue rule. Keep the slot so Material defaults stay valid. |

### Refinements

| # | Change | Why | Stitch evidence |
| --- | --- | --- | --- |
| R1 | Result and detail receipt actions (`ReceiptActions`) as one bordered action group at every size: white, 1 dp `outlineVariant` border, `medium` shape, rows of at least 48 dp with a 20 dp navy icon and navy `labelLarge`, divided by inset hairlines. The order stays print, merchant copy, email, share, show. | Three or four stacked outlined buttons outweigh the outcome. | `stitch-result-approved.png` |
| R6 | Compact (AMS1) result and detail screens: the `StatusBadge` at `IconSize.Large` (40 dp) beside a two-line outcome and amount, instead of 56 dp above them. | On AMS1 only *Print receipt* was visible above the pinned bar; the badge-beside layout saves about 60 dp. A two-column receipt grid was also tried and saved about 44 dp more, but the owner rejected it as too crowded. | `stitch-ams1-result-approved-grid.png` (header only) vs golden `result-approved_ams1.png` |
| R2 | The sale screen's outer margin is `screenPadding`, not the grid `gap`. The Custom item button, product grid and bottom bar share one edge. | Sale is the only screen inset 12 dp on a regular phone (`SaleScreen.kt` `contentPadding = PaddingValues(gap)`). | `stitch-sale-cart.png` |
| R3 | History filter chips use the `outline` border used by the search field; the selected chip uses `primaryContainer` with a tick. History status text gets the small status glyph that results already use. | Two outline weights sit side by side; status is otherwise colour plus word only. | `stitch-history.png` |
| R4 | A selection setting shows its current value in `onSurfaceVariant`, like navigation rows, not in `primary`. | Green value text is decoration under principle 4, and the same fact looks different on adjacent rows. | Terminal setup golden (current) |
| R5 | Text-field errors add Material's trailing error icon. | Error then reads by outline, icon and text, not colour alone. | `stitch-product-price-error.png` |

### AMS1 (Compact) check

Every proposal is checked at 320×460 dp with the `Dimens.Compact` values before it is accepted; the phone renders
alone are not evidence. Stitch renders of `stitch-ams1-*.png`:

- **A grouped action list barely saves AMS1 height.** Stitch's first compact render fitted it only by drawing 24 dp
  rows. At 48 dp, three grouped rows (144 dp) take about as much as three stacked buttons (148 dp). The AMS1 saving
  comes from R6's header.
- **No side-by-side actions.** A two-column receipt grid fitted everything on the AMS1, but the owner judged it too
  crowded and it was removed. Receipt actions stay one per row, and lower ones scroll on the AMS1 when needed.
- **Sale (R2) costs AMS1 4 dp a side.** The AMS1 grid edge is 8 dp today against the 12 dp `screenPadding`. Two-line
  tile names still fit; check long French names in the goldens.
- **History (R3) and result unknown need no AMS1 layout change.** Both already fit their content above the fold.
- **Reject Stitch's compact typography.** It shrank row text to 11–13 px and upper-cased the simulator banner. The
  app's Compact tier keeps body roles and must survive 1.5× font scale (`CompactLayoutTest`).

### Explored and rejected

- **Compact Home tiles.** Stitch shrank the feature tiles to about 88 dp so they fit, leaving the lower third empty.
  The shipped tiles grow together up to `homeMaxHeight`; that wins on a fixed terminal. Home stays as it is.
- **Shorter Settings rows** (64 dp). The gain is small, and it would change every Settings golden and the 1.5× layout.
- **Semibold values in labeled rows; bold switch titles.** These add emphasis without hierarchy. Keep the body weights.
- **Stitch's replacement icons** (Tokens, Refund, Terminal, Simulator). Keep the shipped Material icons.

## Open Questions

1. Should dialogs be extended with a shared `AppDialog` or stay as the current `ConfirmDialog` family?
2. Should the non-token `dp` layout widths (340 cart pane, 420 QR, 360 receipt preview) become named constants?
3. ~~P1–P4 and R1–R6~~: approved and implemented on October 31, 2026. Feature icons in `primary` stay as the
   documented identity exception (principle 4).
