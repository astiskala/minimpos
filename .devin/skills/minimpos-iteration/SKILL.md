---
name: minimpos-iteration
description: Use fast feedback lanes and coordinated change recipes when editing or debugging Mini mPOS.
---

# Mini mPOS iteration

Read [repository constraints](../../../AGENTS.md), relevant module rules and the changed owner's contract first.
[CONTRIBUTING.md](../../../CONTRIBUTING.md#verify-a-change) owns command behavior and verification requirements.
Use [test starters](../minimpos-tests/SKILL.md) when adding tests; adapt the nearest existing test instead of inventing fixtures.

## Feedback loop

1. Run `scripts/dev preflight` once on a new workspace; `scripts/dev warm` optionally fills caches.
2. Reproduce with the smallest matching test: `scripts/dev test MODULE 'PATTERN'`.
3. Edit a coherent batch; `scripts/dev format` before checking it. Never run formatters concurrently with edits.
4. Structural edit: `scripts/dev check architecture`, then the relevant type-resolved detekt task.
5. Run the matching lane below. Fast lanes are feedback, never completion evidence.
6. Failure: read the runner's private `repair.txt` and full log. `scripts/dev report` adds exact JUnit assertions,
   quoted reruns and historical suite timings. Inspect UI artifacts when present.
7. Group findings by root cause, fix a batch, rerun failing checks, then `scripts/dev finish` on the final checkout.
8. Dependency edit: also build release as required by CONTRIBUTING. Report real-device verification gaps honestly.

Use one Gradle invocation at a time per checkout. Reuse the daemon/caches; do not routinely run `clean`,
`--rerun-tasks` or `--no-configuration-cache`. Do not poll a running check repeatedly; work on independent reads,
then wait for completion. Pass multiple tasks in one invocation when collecting independent checks.

## Change recipes

### UI text and merchant guides

- Identify authoritative English resource/page; update Chinese and Japanese in the same batch.
- Resources: match keys, placeholders and plural contracts. Start with `scripts/dev check localization`.
- Pages: update reciprocal switches and canonical/hreflang metadata; `scripts/dev check website`.
- Follow [language and documentation rules](../../../AGENTS.md); do not translate stored identifiers or Adyen fields.

### QR formats

- Trace `TransferCodec`, `QrChunks`, `TransferSeal`, `docs/js/setup.js` and their existing vectors.
- Change both producers/consumers and vectors together; retain the helper's no-network policy.
- Run `scripts/dev check qr`; use negative vectors for malformed chunks, bad seals and stale async generation.
- No earlier-build aliases or compatibility branches. Root constraints own the format policy.

### Room and stored events

- Read [app ownership rules](../../../app/AGENTS.md); edit the event/repository owner, not downstream mutations.
- Follow [schema upgrade requirements](../../../CONTRIBUTING.md#make-changes-that-fit): version and register migrations,
  retain released schemas, export the new schema and update `DatabaseSchemaTest` with migration/data-preservation tests.
- Run `scripts/dev check db`, plus affected repository/event tests. No destructive fallback or merchant-data reset.

### Financial operations

- Read the operation owner's KDoc and original-context eligibility rules.
- Cover answered, not sent and maybe sent; persisted identity/request facts; retry idempotency; context mismatch;
  Manager approval recheck. Distinct authorization renewal has distinct identity.
- Reuse existing fake Terminal, Cloud, Payments app, Checkout and Management APIs. No real API calls.
- Run `scripts/dev check payment`; transport changes also need targeted `adyen` tests and architecture checks.
- Simulator results do not establish real-Adyen behavior; CONTRIBUTING owns the integration gap list.

### Compose UI

- Reuse state/callback components and `LocalDimens`; keep decision cases outside UI tests.
- Reproduce at AMS1 qualifiers, then the affected Chinese/Japanese flow. Run targeted UI tests before the UI lane.
- Use `createRecordingComposeRule` so failures capture roots before Compose teardown.
- Existing reproducible scenarios: `AppFlowTest` (approved/declined/recovery), `SmallScreenTest` (layout),
  `LocalizedUiTest` (CJK), `TransferScreensTest` (QR), `AutomaticSetupUiTest` (setup).
- Only synthetic in-memory/temporary fixtures. For actual emulator/docs captures, invoke
  [docs-screenshots](../docs-screenshots/SKILL.md); never reset existing merchant data.

## Measure, do not guess

`scripts/dev report` reads existing report snapshots, not one guaranteed full run. Check timestamps.
Compare repeated `scripts/dev profile 1 'PATTERN'` and `scripts/dev profile 2 'PATTERN'` runs on the same checkout,
without another build running. Use Gradle's profile for wall time; suite times overlap under parallel workers.
Keep the two-worker default unless repeated measurements justify changing it. Profile fixture startup, navigation
and polling before adding workers or moving tests. Do not remove coverage or checks to improve measurements.
