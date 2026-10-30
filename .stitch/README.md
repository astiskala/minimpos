# `.stitch/`

The design-system home for Mini mPOS: one machine-readable specification that people, coding agents and
[Google Stitch](https://stitch.withgoogle.com) all read.

| Path | Contents |
| --- | --- |
| [DESIGN.md](DESIGN.md) | The authoritative visual specification: colour, type, spacing, shape, components, known exceptions. |
| `reference/` | Approved reference images (Stitch exports, native screenshot baselines you choose to keep here). |

Stitch project: **Mini mPOS — Design Review (Oct 2026)**, ID `14709404705320367655`, design system asset
`c3bb1a7d0561471ab9737fdb349a5456`. It was seeded from a condensed copy of `DESIGN.md` on October 31, 2026; its exports
are in `reference/stitch-review-2026-10-31/`. An earlier project, `18295887401793738558` ("MinimPOS Design System"),
carries a Stitch-generated theme that drifted from the app's tokens; do not use it as a reference.

## Workflow

```text
existing Android UI
      ↓
DESIGN.md                      (this directory)
      ↓
Android design tokens/components   ui/theme/Theme.kt, Tokens.kt, ui/components/
      ↓
implementation                 feature screens use tokens and shared components
      ↓
static design audit            scripts/design-audit, docs/ui/DESIGN_AUDIT.md
      ↓
screenshot regression tests    see docs/ui/SCREENSHOT_TESTING.md
```

- **Stitch consumes and evolves `DESIGN.md`.** Give Stitch the file as its design system. When Stitch proposes a change
  (a new radius, a new button), accept it by editing `DESIGN.md` first, then the code.
- **Android implements the documented tokens.** `Theme.kt` and `Tokens.kt` are the code form of `DESIGN.md`. A value that
  appears in both must match; the tests (`ThemeContrastTest`, `DimensTest`) pin parts of it.
- **Visual changes update both.** A change to a colour, size, shape or shared component edits the code, `DESIGN.md` and
  any affected screenshot baseline in the same change.
- **Exceptions are written down.** A deliberate deviation from `DESIGN.md` goes in its *Known Exceptions* table with a
  reason; an undocumented one is a defect for the audit to flag.
- **Stitch defines intent; Android screenshots detect regressions.** Never pixel-diff Android output against Stitch
  renders. Android baselines compare approved Android rendering with future Android rendering only.

## Checking the implementation

```bash
scripts/design-audit            # report where code bypasses the design system
scripts/design-audit --strict   # exit 1 on hard-rule breaks (colour literals, shapes outside the theme)
```

Hard rules are clean today and safe to gate. Soft counts (`dp` literals, raw Material buttons) are triage input; see
[docs/ui/DESIGN_AUDIT.md](../docs/ui/DESIGN_AUDIT.md) for what each finding means and which are safe to fix
automatically.

## For coding agents

Read [DESIGN.md](DESIGN.md) before changing UI. Use `MaterialTheme.colorScheme`, `MaterialTheme.shapes`,
`MaterialTheme.typography`, `LocalDimens`, `Spacing`, `IconSize`, `Elevation` and the `ui.components` roles. Do not add
colour or shape literals to feature code. Repository rules are in [AGENTS.md](../AGENTS.md#design-system).
