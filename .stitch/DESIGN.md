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
4. **Colour carries outcome, never decoration.** Green is success and primary action, orange is unknown or needs
   attention, red is declined or destructive, blue is links only.
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
| `background`, `surface`, `surfaceContainerLow` | `#FFFFFF` | Screens, app bar, dialogs |
| `surfaceVariant`, `surfaceContainer`, `surfaceContainerHigh`, `secondaryContainer` | `#F3F6F9` (`GreyLight`) | `Card`, product tiles, info `Notice`, keypad keys |
| `surfaceContainerHighest`, `outlineVariant` | `#E3E8EE` (`Border`) | Dividers, card borders |
| `primaryContainer` | `#E6F8EE` (`GreenLight`) | A product already in the cart (with a 1 dp primary outline) |
| `errorContainer` | `#FBE6ED` | Error panels |
| `StatusColors.warningContainer` | `#FFF1E5` | Warning `Notice`, simulator banner |

### Content

| Role | Value | Used for |
| --- | --- | --- |
| `onSurface`, `onBackground` | `#00112C` (`Navy`) | Body and title text |
| `onSurfaceVariant` | `#5C687C` (`Grey`) | Secondary text; the most used colour role after `onSurface` |
| `outline` | `#848FA0` | Field outlines (3:1 on white) |
| `onPrimary`, `onError`, `StatusColors.onStatus` | `#FFFFFF` | Content on green, red and status badges |

### Brand

| Role | Value | Note |
| --- | --- | --- |
| `primary` | `#00843F` (`Green`) | Deepened from Adyen's `#0ABF53` (2.4:1 with white) to pass AA with white text |
| `secondary` | `#00112C` (`Navy`) | Secondary actions |
| `tertiary` | `#0070F5` (`Blue`) | Links only |

The launcher background is the same green (`res/values/colors.xml`).

### Semantic colours

`LocalStatusColors` supplies what Material has no slots for. `StatusKind` maps outcome to colour and glyph.

| Meaning | Colour | Glyph | Use |
| --- | --- | --- | --- |
| Success | `#00843F` | Tick | Approved, completed, `ActionMessage` |
| Warning | `#B35300` on `#FFF1E5` | Exclamation | Unknown, pending, needs attention, `BlockedReason` icon |
| Error | `#D10244` on `#FBE6ED` | Cross | Declined, failed, destructive actions (`ConfirmedRemoval`, destructive `TertiaryButton`) |

Do not signal status by colour alone: the badge glyph and the text always accompany it.

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

**Fixed scale (`Spacing`, `Tokens.kt`)**: 4 `XSmall`, 8 `Small`, 12 `Medium`, 16 `Large`, 24 `XLarge`, 32 `XXLarge`.
Use it for gaps that do not depend on screen size. **Inferred:** a 20 dp step also appears (section header top
padding, button icon, notice icon) and is not on the scale; see the audit.

## Shapes

`MaterialTheme.shapes` (**Observed**): extraSmall 6, small 8, medium 12, large 16, extraLarge 24 dp.

| Shape | Used for |
| --- | --- |
| `medium` (12) | **Canonical.** Buttons, keypad keys, `Card`, `Notice`, product tiles, dialogs' content (14 uses) |
| `large` (16) | Home payment tiles, custom-amount dialog, scanner frame |
| `small` (8) | Receipt preview border |
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
  labels (`IconSize.Inline`)**, 24 rows and steps (`IconSize.Standard`), 32 payment tiles, 40 empty state and PIN lock
  (`IconSize.Large`; 56 on regular empty state). 18 is the one off-grid size.
- Status badge glyph is 55% of the badge (88/64/56 dp by size set).
- The application icon is PNG, not adaptive; `MainActivity` has its own adaptive icon (AGENTS.md).

## Components

All in `ui/components/` unless noted. Prefer these over raw Material components in feature code.

### Buttons

| Component | Material base | Height | Notes |
| --- | --- | --- | --- |
| `PrimaryButton` | `Button` | `Dimens.buttonHeight` 56/52/48 | Full width, `medium` shape, optional icon, spinner while `loading` |
| `SecondaryButton` | `OutlinedButton` | `secondaryButtonHeight` 52/48/44 | Same layout |
| `TertiaryButton` | `TextButton` | `secondaryButtonHeight` | `destructive` uses the error colour |
| `GatedButton` (`feature/`) | wraps the above | | Use for any action reaching Adyen or a terminal |
| `QuantityStepper` | `IconButton` | 48 dp target | − qty +, names the item for screen readers |

Labels use `labelLarge`; the icon is 20 dp with an 8 dp gap; the loading spinner replaces the icon with a 12 dp gap.
Removal of saved keys, PINs, registrations or history uses red `ConfirmedRemoval`. **Decision needed:** spinner sizes
differ by button (22 primary, 18 others).

### Text fields

`OutlinedTextField` (22 uses) with the default Material 3 outline using the `outline` colour. Settings fields use an
external title (same style as toggles), then a primary description, then the input; notes below hold only extra
information or validation (app/AGENTS.md). No custom field component exists. **Decision needed:** whether to extract
`AppTextField` (see audit).

### Cards

`Card` (not Material's): full width, `surfaceVariant`, `medium` shape, `cardPadding`, no border or elevation. Selected
product tiles use `primaryContainer` with a 1 dp primary border. `Notice` is the tinted-panel variant.

### Dialogs

`ConfirmDialog`, `ResultDialog`, `InfoDialog`, `TextInputDialog`, `ActionOptionsDialog`, `EmailReceiptDialog` and
`SharedKeyDialog` wrap Material `AlertDialog` (12 uses). Result dialogs show a 40 dp `StatusBadge`. Dialog buttons are
Material `TextButton` slots, an accepted idiom. Settings › Terminal reports setup outcomes in a dialog, one at a time.
Dialogs containing text fields use `DialogProperties(usePlatformDefaultWidth = false)` in tests.

### Navigation

`MiniScaffold`: `CenterAlignedTopAppBar` on `surface`, height `Dimens.topBarHeight` 64/56/48, back arrow when
`onBack != null`, `ModeBanner` (simulator only) and a divider under the bar, content above the keyboard.
Navigation 3 transitions are owned by `AppNavHost`. Home and onboarding are roots. Expanded windows show History and
Settings details beside the list.

### Lists and rows

`SwitchRow` (min 48 dp, toggleable as one target), `LabeledValue`, `TransactionRow`, `SettingActions`, `ListItem`
(2 uses). Rows use `rowPadding` vertical and `rowHorizontalPadding` horizontal. Section titles are `SectionHeader`.

### Keypad and amounts

`Keypad` / `NumericKeypad`: `FilledTonalButton` keys, `medium` shape, `keyHeight` 56/52/44, `keySpacing` gap, digits
in `headlineSmall`. Compact screens use the full-screen keypad so the primary action stays in view. PIN entry uses 16 dp
dots.

### Status, empty and loading states

- `OutcomeHeader` + `StatusBadge` (88/64/56 dp) + `OutcomeNote`, then `ResultBar` (Home + next step).
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
announce state changes; new text uses string resources in all seven languages.

## Dark Mode

Not supported by design: `MiniMposColors` is `lightColorScheme`, the XML window theme is
`Theme.Material.Light.NoActionBar` with light status/navigation bars, and `window_background` is white. No dynamic colour.
This follows the terminals' own payment screens. **Decision needed:** whether phone/tablet installs should get a dark
scheme; any such change must keep `ThemeContrastTest` passing and Stitch needs a second token set.

## Known Exceptions

| Exception | Where | Why |
| --- | --- | --- |
| Raw `Button`/`TextButton`/`OutlinedButton` (19 in the audit) | Dialog action slots (settings, tax, products, currency offer), list-row choices (terminal picker, gift-card activities, wallet choice), the update banner's Download, transfer play/pause, the API-roles expander, the cart button, the split-payment exact-cash button | Dialog slot idiom, or a row or chip with its own size and content that is not a full-width action; migrating would change how they look |
| `RectangleShape` full-screen dialog | `SaleScreen` custom amount, compact only | Keypad takes the whole screen |
| `Color.White`/`Black` | Scanner and receipt rendering | Camera overlay and print preview are not themed UI |
| 5 `sp` literals | Home tile autosize bounds, split-payment autosize, product tile line height | Autosize needs concrete bounds |
| Simulator banner | `ModeBanner` | The only mode indicator by design |
| No Home terminal-status line, no TEST banner | Home | Deliberate product choices |

## Open Questions

1. Is the 18 dp icon/spinner size intended, or should it become 20 dp (`IconSize.Inline`)?
2. Should the 20 dp spacing step become part of `Spacing`, or be replaced by 16 or 24?
3. Should dialogs be extended with a shared `AppDialog` or stay as the current `ConfirmDialog` family?
4. Do phone and tablet installs need dark mode, or does light-only stay a product rule?
5. Should the non-token `dp` layout widths (340 cart pane, 420 QR, 360 receipt preview) become named constants?
6. Which raw Material buttons in feature screens should migrate to `PrimaryButton`/`SecondaryButton`/`TertiaryButton`?
