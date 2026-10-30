# Design-system enforcement

How to keep code aligned with [.stitch/DESIGN.md](../../.stitch/DESIGN.md) without a custom lint framework. Ordered by
effort; only step 1 exists today.

| Rule | Mechanism | Why this one | Status |
| --- | --- | --- | --- |
| No `Color(0x…)` or `RoundedCornerShape` outside `ui/theme` | `scripts/design-audit --strict` | Both counts are 0, a grep is exact and cheap | Implemented, not yet in CI |
| Only `ui.components` marks headings; Material 3 only; layering | ArchUnit (`HeadingArchitectureTest`, `MaterialArchitectureTest`) | Existing home for architectural decisions (AGENTS.md requires a rule plus a deliberate-violation check) | Implemented |
| Text and icon contrast | `ThemeContrastTest` | Pure maths on the scheme | Implemented; add pairs when tokens are added |
| Feature code uses `Spacing`/`Dimens` instead of `dp` literals | `scripts/design-audit` soft count, then a ratchet | 129 literals today; many are legitimate widths, so a hard rule would force baselines (forbidden) | Report only |
| Feature code uses `PrimaryButton`/`SecondaryButton`/`TertiaryButton` | ArchUnit rule "feature classes do not call `androidx.compose.material3.ButtonKt`", added once the 21 raw uses are migrated or documented | Bytecode check is exact and needs no new tooling | Proposed |
| Touch targets of at least 48 dp | A Robolectric helper in the screenshot gallery test that asserts the size of every clickable node's bounds | The tree knows real sizes; static analysis cannot, because tokens and modifiers compose | Proposed |
| No `sp` literals outside the theme | Same ArchUnit-free grep (soft count of 5) | Autosize bounds are legitimate | Report only |

## Why not a custom detekt or Android Lint rule

The repository already runs type-resolved detekt with the Compose rules plugin and a deliberately small ignore policy
("fix findings, don't silence them"; no baselines). A custom detekt rule would be a new Kotlin module and a maintenance
cost for rules that a grep or ArchUnit check expresses in a few lines. Revisit if a rule needs types (for example
"`Modifier.padding` argument must be a token"), which would need a detekt rule over resolved calls; the 129-literal
count does not justify it yet.

## The `design-audit` command

```bash
scripts/design-audit            # full report: counts and file:line findings
scripts/design-audit --strict   # exit 1 when a hard rule is broken
```

A coding agent comparing an implementation with the specification should:

1. Read [.stitch/DESIGN.md](../../.stitch/DESIGN.md) and its *Known Exceptions*.
2. Run `scripts/design-audit` and compare the summary with the previous one in
   [DESIGN_AUDIT.md](DESIGN_AUDIT.md); new hard findings are defects, new soft findings need a reason or a token.
3. Fix only high-confidence, value-preserving findings; list the rest as *REQUIRES_DESIGN_DECISION*.
4. Run `scripts/dev check ui` and `scripts/dev check architecture`, then `scripts/dev check ui-snapshots`.

## Suggested CI step

After two clean weeks, add `scripts/design-audit --strict` to `scripts/ci`. It runs in under a second and needs no
Gradle.
