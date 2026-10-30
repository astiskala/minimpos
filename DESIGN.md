---
version: alpha
name: Mini mPOS
description: >-
  Terminal-first Material 3 design system for Mini mPOS on Adyen Android terminals, tablets and phones: white
  surfaces, green actions, dark navy text, light only. Sizes are Android dp (layout) and sp (text), written as px.
colors:
  primary: "#00843F"
  on-primary: "#FFFFFF"
  primary-container: "#E6F8EE"
  on-primary-container: "#00112C"
  secondary: "#00112C"
  on-secondary: "#FFFFFF"
  secondary-container: "#F3F6F9"
  on-secondary-container: "#00112C"
  background: "#FFFFFF"
  on-background: "#00112C"
  surface: "#FFFFFF"
  on-surface: "#00112C"
  surface-variant: "#F3F6F9"
  on-surface-variant: "#5C687C"
  surface-container: "#F3F6F9"
  surface-container-high: "#FFFFFF"
  surface-container-highest: "#E3E8EE"
  outline: "#848FA0"
  outline-variant: "#E3E8EE"
  error: "#D10244"
  on-error: "#FFFFFF"
  error-container: "#FBE6ED"
  on-error-container: "#D10244"
  success: "#00843F"
  warning: "#B35300"
  warning-container: "#FFF1E5"
  on-status: "#FFFFFF"
typography:
  amount-lg:
    fontFamily: Roboto
    fontSize: 44px
    fontWeight: 700
    letterSpacing: -1px
  amount-md:
    fontFamily: Roboto
    fontSize: 38px
    fontWeight: 700
    letterSpacing: -1px
  amount-sm:
    fontFamily: Roboto
    fontSize: 34px
    fontWeight: 700
    letterSpacing: -1px
  display-md:
    fontFamily: Roboto
    fontSize: 45px
    fontWeight: 700
    lineHeight: 52px
    letterSpacing: -0.5px
  headline-md:
    fontFamily: Roboto
    fontSize: 28px
    fontWeight: 700
    lineHeight: 36px
  headline-sm:
    fontFamily: Roboto
    fontSize: 24px
    fontWeight: 600
    lineHeight: 32px
  title-lg:
    fontFamily: Roboto
    fontSize: 22px
    fontWeight: 600
    lineHeight: 28px
  title-md:
    fontFamily: Roboto
    fontSize: 16px
    fontWeight: 600
    lineHeight: 24px
    letterSpacing: 0.15px
  title-sm:
    fontFamily: Roboto
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
    letterSpacing: 0.1px
  label-lg:
    fontFamily: Roboto
    fontSize: 16px
    fontWeight: 600
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: Roboto
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
    letterSpacing: 0.5px
  body-lg:
    fontFamily: Roboto
    fontSize: 16px
    fontWeight: 400
    lineHeight: 24px
    letterSpacing: 0.5px
  body-md:
    fontFamily: Roboto
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
    letterSpacing: 0.25px
  body-sm:
    fontFamily: Roboto
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
    letterSpacing: 0.4px
rounded:
  none: 0px
  xs: 6px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  full: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  section: 20px
  xl: 24px
  xxl: 32px
  screen-padding: 16px
  screen-padding-compact: 12px
  border: 1px
  touch-target: 48px
  content-max-width: 560px
  list-max-width: 640px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label-lg}"
    rounded: "{rounded.md}"
    height: 56px
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.label-lg}"
    rounded: "{rounded.md}"
    height: 52px
  button-tertiary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    typography: "{typography.label-lg}"
    rounded: "{rounded.md}"
    height: 52px
  button-tertiary-destructive:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.error}"
    typography: "{typography.label-lg}"
    rounded: "{rounded.md}"
    height: 52px
  action-group:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.label-lg}"
    rounded: "{rounded.md}"
    height: 52px
  chip:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.sm}"
    height: 32px
  chip-selected:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.sm}"
    height: 32px
  text-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-lg}"
    rounded: "{rounded.xs}"
  text-field-error:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.error}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.xs}"
  card:
    backgroundColor: "{colors.surface-variant}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.md}"
    padding: 16px
  product-tile:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-sm}"
    rounded: "{rounded.md}"
    height: 68px
  product-tile-in-cart:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    typography: "{typography.title-sm}"
    rounded: "{rounded.md}"
    height: 68px
  notice-info:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
  notice-warning:
    backgroundColor: "{colors.warning-container}"
    textColor: "{colors.warning}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
  error-panel:
    backgroundColor: "{colors.error-container}"
    textColor: "{colors.on-error-container}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
  dialog:
    backgroundColor: "{colors.surface-container-high}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.xl}"
  top-app-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-lg}"
    height: 64px
  simulator-banner:
    backgroundColor: "{colors.warning-container}"
    textColor: "{colors.warning}"
    typography: "{typography.label-md}"
  home-payment-tile:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.title-lg}"
    rounded: "{rounded.lg}"
  keypad-key:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    typography: "{typography.headline-sm}"
    rounded: "{rounded.md}"
    height: 56px
  amount:
    backgroundColor: "{colors.background}"
    textColor: "{colors.on-background}"
    typography: "{typography.amount-lg}"
  status-badge-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.on-status}"
    rounded: "{rounded.full}"
    size: 88px
  status-badge-warning:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.on-status}"
    rounded: "{rounded.full}"
    size: 88px
  status-badge-error:
    backgroundColor: "{colors.error}"
    textColor: "{colors.on-error}"
    rounded: "{rounded.full}"
    size: 88px
  list-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-lg}"
    padding: 12px
    height: 48px
  list-row-value:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface-variant}"
    typography: "{typography.body-md}"
  divider:
    backgroundColor: "{colors.outline-variant}"
    height: 1px
---

# Mini mPOS Design System

The authoritative visual specification for Mini mPOS, in the [DESIGN.md format](https://github.com/google-labs-code/design.md)
(version `alpha`). The YAML front matter holds the normative tokens; the sections below give their rationale and the
rules tokens cannot express. People and coding agents read this file before changing UI.

## Overview

Mini mPOS is a Kotlin Compose app for Adyen Android terminals, tablets and phones. Its look follows the Adyen terminal
payment UI: white surfaces, green actions, dark navy text, flat and light only. The smallest screen is the AMS1, about
320×460 dp, so density and one clear action per screen matter more than decoration.

### Units and code mapping

Front-matter sizes are written in `px` because the format allows only `px`, `em` and `rem`; read them as Android `dp`
for layout and `sp` for text. Token names map to code as follows, and a value in both places must match:

| Tokens | Code |
| --- | --- |
| `colors` | `MiniMposColors` and `AdyenColors` in [Theme.kt](app/src/main/java/app/minimpos/app/ui/theme/Theme.kt); `success`, `warning`, `warning-container`, `on-status` are `StatusColors` (`LocalStatusColors`) |
| `typography` | `typography` and `AmountTextStyle` in `Theme.kt`: `display-md` is `displayMedium`, `label-lg` is `labelLarge`, and so on; `amount-lg/md/sm` are `Dimens.amountStyle` per size set |
| `rounded` | `MaterialTheme.shapes`: `xs` extraSmall … `xl` extraLarge; `full` is `CircleShape`, `none` `RectangleShape` |
| `spacing` | `Spacing`, `BorderWidth` and `MinTouchTarget` in [Tokens.kt](app/src/main/java/app/minimpos/app/ui/theme/Tokens.kt); `screen-padding*` and the max widths are `Dimens` |
| `components` | [ui/components/](app/src/main/java/app/minimpos/app/ui/components/); sizes are the Regular size set (see [Layout](#layout)) |

`ThemeContrastTest` and `DimensTest` pin parts of the code. Behaviour and layout rules that are not visual live in
[app/AGENTS.md](app/AGENTS.md) and are not repeated here.

### Keeping code and spec in step

- **Visual changes update both.** A change to a colour, size, shape or shared component edits the code, this file and
  any affected screenshot golden in the same change. Change the token here first, then the code.
- **Exceptions are written down.** A deliberate deviation goes in [Known Exceptions](#known-exceptions) with a
  reason; an undocumented one is a defect.
- **Check the spec.** `npx @google/design.md lint DESIGN.md` validates the front matter, references and component
  contrast.
- **Tests enforce parts of it.** `ThemeContrastTest` checks contrast pairs (add pairs with new tokens); ArchUnit
  (`HeadingArchitectureTest`, `MaterialArchitectureTest`, `ButtonArchitectureTest`) keeps headings, Material 3 and
  button frames in `ui.components`; `DesignGalleryTest` checks touch targets.
- **Goldens detect regressions.** Android screenshot goldens compare approved Android rendering with future Android
  rendering ([docs/ui/SCREENSHOT_TESTING.md](docs/ui/SCREENSHOT_TESTING.md)). External design renders show intent only
  and are never pixel baselines.

### Design principles

1. **Terminal-first.** The palette follows the Adyen terminal payment UI. The AMS1's primary action must be visible
   without scrolling (`SmallScreenTest`).
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
   full-width payment tiles on Home. See [AGENTS.md](AGENTS.md#payment-and-product-boundaries).

## Colors

A Material 3 `lightColorScheme` built from `AdyenColors`, with no dynamic colour. Feature code reads colours from
`MaterialTheme.colorScheme` or `LocalStatusColors`, never from `AdyenColors` or `Color(0x…)`.

- **Primary** (`#00843F`, Adyen green deepened from the brand's `#0ABF53`, which is 2.4:1 with white): primary
  actions, success, links, tertiary actions and feature icons. The launcher background is the same green.
- **Secondary** (`#00112C`, navy): body and title text (`on-surface`) and secondary actions, whose label and icon use
  the same navy.
- **Tertiary** is left at Material's default and unused; links and tertiary actions are green.
- **Neutrals.** `surface`, `background` and `surface-container-high` are white: screens, the app bar and dialogs, so
  cards and notices inside a dialog keep their contrast. `surface-variant`, `surface-container` and
  `secondary-container` (`#F3F6F9`) are cards, product tiles, info notices, keypad keys and menus. `outline-variant`
  (`#E3E8EE`) is dividers and the `ActionGroup` border. `on-surface-variant` (`#5C687C`) is secondary text, the most
  used role after `on-surface`. `outline` (`#848FA0`, 3:1 on white) outlines fields, secondary buttons and chips.
- **Status.** `StatusKind` maps outcome to colour and glyph:

| Meaning | Colour | Glyph | Use |
| --- | --- | --- | --- |
| Success | `success` | Tick | Approved, completed, `ActionMessage` |
| Warning | `warning` on `warning-container` | Exclamation | Unknown, pending, needs attention, `BlockedReason` icon, simulator banner |
| Error | `error` on `error-container` | Cross | Declined, failed, destructive actions (`ConfirmedRemoval`, destructive `TertiaryButton`) |

Status is never signalled by colour alone: the glyph (`StatusKind.icon()`) and the text always accompany it, on result
badges and beside a list row's status. Invalid text fields add Material's error icon (`fieldErrorIcon`) to the red
outline and the supporting text.

**Dark mode is not supported, by decision.** The XML window theme is `Theme.Material.Light.NoActionBar` with light
system bars and a white `window_background`. This follows the terminals' own payment screens; the owner keeps light
only on phones and tablets too.

## Typography

The Material 3 default type scale with weight overrides; the font is the system default (Roboto on Android), with no
custom font.

| Token | Observed use |
| --- | --- |
| `amount-lg/md/sm` | Amounts on checkout, keypad and results (Regular/Medium/Compact); based on `display-md` |
| `headline-md` | Regular-size outcome title ("Approved") |
| `headline-sm` | Compact outcome title; keypad digits |
| `title-lg` | Payment tile titles; regular app bar title |
| `title-md` | Compact app bar title; quantity |
| `title-sm` | `Subheading` (block, step, card title); product tile name |
| `label-lg` | All button labels |
| `label-md` | `SectionHeader` (upper case) |
| `body-lg`, `body-md` | Row text, `ActionMessage`, `BlockedReason` |
| `body-sm` | Explanations on compact screens; secondary lines |

- Text roles are fixed: `SectionHeader` (upper case, groups rows or fields), `Subheading` (sentence case, a block),
  `SheetTitle`, `Description`, `Notice`, `BlockedReason`, `ActionMessage`, `OutcomeNote`. Only `ui.components` marks
  headings (ArchUnit).
- Explanations are one size smaller on compact screens (`explanationStyle`).
- Money is always `Long` minor units formatted by `MoneyFormatter`; amounts use the amount style, other money uses the
  surrounding body role.
- Text must survive 1.5× font scale on the smallest screens (`CompactLayoutTest`). Prefer `TextAutoSize` and wrapping
  to truncation for titles (Home payment tiles).

## Layout

Two spacing layers. The fixed scale (`Spacing` in `Tokens.kt`) is the `xs`–`xxl` tokens: use it for gaps that do not
depend on screen size. `section` (20) is the deliberate half-step above a regular screen's section header and around
picker dialogs.

Screen-dependent sizes come from `Dimens`, chosen by `Dimens.forWindow` for the area between the system bars:

| Token | Regular | Medium | Compact |
| --- | --- | --- | --- |
| Window rule | height ≥ 640 dp | height 520–639 dp | width < 360 dp or height < 520 dp |
| `screenPadding` | 16 | 16 | 12 |
| `spacing` | 12 | 10 | 8 |
| `rowPadding` | 12 | 10 | 8 |
| `cardPadding` | 16 | 14 | 12 |
| `rowHorizontalPadding` | 16 | 16 | 16 |
| `keySpacing` | 8 | 8 | 6 |
| `buttonHeight` | 56 | 52 | 48 |
| `secondaryButtonHeight` | 52 | 48 | 44 |
| `keyHeight` | 56 | 52 | 44 |
| `topBarHeight` | 64 | 56 | 48 |
| `badgeSize` | 88 | 64 | 56 |
| `progressSize` | 64 | 56 | 48 |

Widths: `contentMaxWidth` 560, `listMaxWidth` 640, `narrowActionMaxWidth` 320, `homeMaxHeight` 640, `fabClearance` 88.

- **Screen margin.** Every screen's content, including the sale screen's search, category chips, Custom item button and
  product grid, starts `screenPadding` from the edge; gaps between items use `spacing`.
- **Outline width.** `border` (1 dp) for secondary buttons, chips, the `ActionGroup` border and products in the cart.
- **Edge to edge** with transparent system bars; the theme measures the area between them (`MiniMposTheme`).
- **Columns** cap at `contentMaxWidth` (lists `listMaxWidth`) and centre on tablets; History and Settings go two-pane
  in expanded windows via Material 3 adaptive navigation.
- **Reference sizes:** AMS1 320×460 dp (Compact), P630, S1F2 360×640 (Medium), S1F4 Pro and phones (Regular).
- **Primary actions** live in `BottomActions` (`MiniScaffold.bottomBar`); the bar hides while typing.

## Elevation & Depth

The UI is flat. Only the pinned action bar casts a shadow: `Elevation.ActionBar` = 8 dp (`BottomActions`, the sale
screen's cart bar). Separation otherwise comes from `HorizontalDivider`, the `outline-variant` colour and tonal
surfaces. **Decision needed:** whether dialogs and sheets should use Material defaults indefinitely.

## Shapes

| Token | Used for |
| --- | --- |
| `md` (12) | **Canonical.** Buttons, keypad keys, `Card`, `Notice` and product tiles |
| `lg` (16) | Home payment tiles, custom-amount dialog, scanner frame |
| `sm` (8) | Receipt preview border |
| `xs` (6) | Material's outlined text fields |
| `xl` (24) | Dialog surfaces: the `AlertDialog` family and `PickerDialog` |
| `full` | Status badge, step number, PIN dots |
| `none` | Custom-amount dialog on compact screens (full-screen takeover) |

Never construct `RoundedCornerShape` in feature code; none exists today.

## Components

All in `ui/components/` unless noted. Prefer these over raw Material components in feature code. Front-matter sizes
are the Regular set; [Layout](#layout) has the Medium and Compact values.

### Buttons

| Component | Material base | Height | Notes |
| --- | --- | --- | --- |
| `PrimaryButton` | `Button` | `buttonHeight` | Full width, `md` shape, optional icon, spinner while `loading` |
| `SecondaryButton` | `OutlinedButton` | `secondaryButtonHeight` | Same layout; navy (`on-surface`) label and icon, 1 dp `outline` border (disabled: `on-surface` at 12 %) |
| `TertiaryButton` | `TextButton` | `secondaryButtonHeight` | `destructive` uses the error colour |
| `CompactSecondaryButton` | `OutlinedButton` | At least 48 dp | Natural width, `md` shape, `label-lg`, the same navy label and border; update action, transfer play/pause and inline Exact cash |
| `ActionGroup` | clickable rows | At least 48 dp per action | Related secondary actions (receipt actions) as one bordered list; see below |
| `ChoiceChip` | `FilterChip` | 32 dp drawn, 48 dp touch | Filters and single choices: `outline` border; selected adds a tick, `primary-container` fill and `primary` border |
| `CompactTertiaryButton` | `TextButton` | At least 48 dp | Natural width, same shape and label role; dialog actions. Not for a lone inline action beside a label, which reads as text: use `CompactSecondaryButton` |
| `GatedButton` (`feature/`) | wraps the above | | Use for any action reaching Adyen or a terminal |
| `QuantityStepper` | `IconButton` | 48 dp target | − qty +, names the item for screen readers |

Labels use `label-lg`; the icon is 20 dp with an 8 dp gap; the loading spinner is the same 20 dp and 8 dp gap, so a
button's label does not move while it loads. The three levels read in order: green fill, navy outline, green text.

`ActionGroup` keeps its actions in order, as one white group at every screen size. It has a 1 dp `outline-variant`
border and `md` shape, with rows of `secondaryButtonHeight` (at least 48 dp): a 20 dp navy icon, a 12 dp gap, then the
navy `label-lg` label. Inset dividers start at the labels. Rows have no chevron because they act rather than navigate.
`ReceiptActions` uses it for print, merchant copy, email, share and show/hide receipt.
Feature buttons use shared frames, enforced by `ButtonArchitectureTest`. Custom content slots retain wallet artwork,
cart counts and row layouts while sharing shape and touch sizing.
Removal of saved keys, PINs, registrations or history uses red `ConfirmedRemoval`.

### Text fields

`OutlinedTextField` with the default Material 3 outline in the `outline` colour. A field that can be invalid passes
`trailingIcon = fieldErrorIcon(isError)`, unless its trailing slot holds an action such as the SKU scanner. Settings
fields use an external title (same style as toggles), then a primary description, then the input; notes below hold
only extra information or validation (app/AGENTS.md). No custom field component exists. **Decision needed:** whether
to extract `AppTextField`.

Invalid product prices show localized supporting text: positive values and the currency's precision, whole numbers for
zero-decimal currencies, or a smaller-price instruction on minor-unit overflow. Accepted price syntax is unchanged.

### Cards

`Card` (not Material's): full width, `surface-variant`, `md` shape, `cardPadding`, no border or elevation. Product
tiles already in the cart use `primary-container` with a 1 dp `primary` border. `Notice` is the tinted-panel variant.

### Dialogs

`ConfirmDialog`, `ResultDialog`, `InfoDialog`, `TextInputDialog`, `ActionOptionsDialog`, `EmailReceiptDialog` and
`SharedKeyDialog` wrap Material `AlertDialog`. Result dialogs show a 40 dp `StatusBadge`. Dialog buttons are shared
`CompactTertiaryButton` slots, retaining destructive and disabled states. Settings › Terminal reports setup outcomes in
a dialog, one at a time. Dialog surfaces use `xl` and `surface-container-high` (white), so inner cards and notices keep
their grey; inner cards retain `md`. Dialogs containing text fields use
`DialogProperties(usePlatformDefaultWidth = false)` in tests.

### Navigation

`MiniScaffold`: `CenterAlignedTopAppBar` on `surface`, height `topBarHeight`, back arrow when `onBack != null`,
`ModeBanner` (simulator only) and a divider under the bar, content above the keyboard. Navigation 3 transitions are
owned by `AppNavHost`. Home and onboarding are roots. Expanded windows show History and Settings details beside the
list. At the expanded-width breakpoint (840 dp), an active list/detail scene uses one `NavigationFrame` app bar and
simulator banner. `MiniScaffold` list panes retain actions; detail panes show a semantic heading in
`Dimens.titleStyle`. Explicit `ScreenHeader` parameters keep presentation separate from access gates. The shared Back
arrow leaves the section. System Back retains Navigation 3's and each screen's existing behavior; expanded Settings
exits the scene, while phone Settings closes its detail first.

### Lists and rows

`SwitchRow` (min 48 dp, toggleable as one target), `LabeledValue`, `TransactionRow`, `SettingActions`, `ListItem`.
Rows use `rowPadding` vertical and `rowHorizontalPadding` horizontal. Section titles are `SectionHeader`. A disabled
`SwitchRow` fades its title and description together (`on-surface` at 38 %). A `TransactionRow` shows its status as a
16 dp glyph and the word in the status colour. A choice setting shows its current value in `on-surface-variant`, like a
navigation row. A saved secret's "Set" and "Not set" stay green and red because they are states.

### Keypad and amounts

`Keypad` / `NumericKeypad`: `FilledTonalButton` keys, `md` shape, `keyHeight`, `keySpacing` gap, digits in
`headline-sm`. Compact screens use the full-screen keypad so the primary action stays in view. PIN entry uses 16 dp
dots.

### Status, empty and loading states

- `OutcomeHeader` + `StatusBadge` + `OutcomeNote`, then `ResultBar` (Home + next step). With room the badge
  (`badgeSize`) sits centred above the outcome and amount. On height-limited screens (`Dimens.compact`) a 40 dp badge
  (`IconSize.Large`) sits beside a start-aligned outcome over amount, centred as a block, so result and detail screens
  keep their receipt actions above the pinned bar on the AMS1. The badge glyph is 55 % of the badge.
- `ProcessingContent`: `CircularProgressIndicator` of `progressSize`, 5 dp stroke, while the terminal works.
- `EmptyState`: centred 56 dp icon (40 compact), title, description, optional action.
- `ActionMessage` (success/error) and `BlockedReason` give inline feedback; screen readers announce both.
- Stepped screens: `StepHeader`/`StepBody`, later steps hidden until unlocked, a tick once done.

### Home

Two full-width green payment tiles (`lg` shape, `title-lg` with shared autosize), then Tokens/Gift cards,
Refund/Cashout and History/Reconciliation, row-major. Settings is the top-right icon under the admin lock. Gated tiles
stay pressable and explain.

### Icons

- Material icons (`Icons.Default`, `Outlined`, `AutoMirrored`); icons are decorative (`contentDescription = null`)
  whenever text sits beside them.
- Sizes (`IconSize`, dp): 16 compact notice and finished step (`Small`), 18 blocked reason, **20 beside button labels
  (`Inline`)**, 24 rows and steps (`Standard`), 32 payment tiles (`Tile`), 40 empty state and PIN lock (`Large`; 56 on
  the regular empty state). 18 is the one off-grid size.
- The application icon is PNG, not adaptive; `MainActivity` has its own adaptive icon (AGENTS.md).

## Do's and Don'ts

- **Do** read colours, shapes and type from `MaterialTheme`, `LocalStatusColors`, `LocalDimens`, `Spacing`,
  `IconSize` and `Elevation`, and use the `ui.components` roles. **Don't** add `Color(0x…)`, `RoundedCornerShape` or
  new fixed text sizes to feature code: add or change a token here and in the theme.
- **Do** keep tap targets at least `touch-target` (48 dp). Compact buttons and keypad keys may draw at 44 dp with a
  48 dp touch area; evaluate hit areas with `touchBoundsInRoot`, not drawn bounds (`DesignGalleryTest`).
- **Do** check every proposal at 320×460 dp with the Compact values; phone renders alone are not evidence.
- **Do** pair status colour with a glyph and text, and announce state changes.
- **Don't** put actions side by side, even on the AMS1: a two-column receipt grid saved height but the owner found it
  too crowded. Receipt actions stay one per row, and lower ones scroll on the AMS1 when needed.
- **Don't** shrink row text or upper-case the simulator banner on compact screens: the Compact tier keeps the body
  roles and must survive 1.5× font scale.
- **Don't** shrink Home's feature tiles to fit; they grow together up to `homeMaxHeight`.
- **Don't** shorten Settings rows (to 64 dp, for example): the gain is small and it changes every Settings layout.
- **Don't** make values in labeled rows semibold or switch titles bold; they add emphasis without hierarchy.
- **Don't** replace the shipped Material icons (Tokens, Refund, Terminal, Simulator) with generated alternatives.
- **Don't** copy values from external design renders: they substitute fonts, regenerate Material roles and invent
  icons.

## Accessibility

**Observed:** WCAG AA contrast tests; headings via `semantics { heading() }` only in `ui.components`; polite live
regions on `BlockedReason` and `ActionMessage`; merged semantics on `Notice` and `SwitchRow`; item names on stepper
buttons; `stateDescription` on cart products; audio guidance for payments; large-text tests at 1.5×. New text uses
string resources in all seven languages.

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

## Open Questions

1. Should dialogs be extended with a shared `AppDialog` or stay as the current `ConfirmDialog` family?
2. Should the non-token `dp` layout widths (340 cart pane, 420 QR, 360 receipt preview) become named constants?
