# Mini mPOS UI review and streamlining plan

## Objective and authorization

Review the running app across its major flows, then streamline it using observed problems and before/after evidence.
Prioritize the smallest Adyen terminal screen while preserving the established visual design and financial safeguards.
The user has authorized app code changes. Commit each logical group separately so another agent can resume the work.

This plan is a working checklist, not a replacement for AGENTS.md, module rules, CONTRIBUTING.md or code contracts.
Read those sources before implementation. Do not copy credentials from the conversation into this file or other
artifacts.

## Starting state

- Planning date: October 29, 2026. The checkout was clean at `ef44dc3d` before this plan was added.
- Preflight passed and the current debug app built successfully.
- A disposable emulator clone was used for preliminary inspection and then shut down. The Pixel was untouched.
- No app code changed and no real-terminal requests were sent during planning.
- One preliminary finding: Home truncates “Add products or a custom amount” at AMS1 width. The full audit is
  pending.
- Preliminary screenshots are under ignored `build/ui-review/before/`; verify freshness before treating them as
  evidence.
- The disposable AVD is `MiniMpos_Docs_Disposable_uireview_20261029`. Its read-only run discarded runtime changes on
  shutdown.
  Do not assume its saved app data or fixture archives form a complete English baseline.
- The connected phone identified during planning was a Pixel 7 Pro. Rediscover devices before targeting one.

## 1. Establish the review method and baseline

Use the repository's minimpos-iteration and docs-screenshots skills. Integrate
[Impeccable](https://github.com/pbakaus/impeccable) for its critique workflow and native Android guidance.
Its skill was researched during planning but was not installed. Inspect its current instructions before installation;
use a user-level installation and avoid adding hooks or unrelated tooling to this repository.

Apply the native review method to the running Compose app. Its web detector does not apply to Compose. Respect the
repository's deliberate choices over generic design advice. Use native guidance to assess hierarchy, discoverability,
cognitive load, wording, accessibility and recovery.

Establish a reproducible baseline:

1. Record the starting commit and working-tree state. Preserve any user changes.
2. Run preflight and inspect the current emulator/device inventory.
3. Create or reuse a suitable owned disposable emulator clone using the documented workflow. Never reset a source AVD
   or the Pixel. Fixture resets are permitted only on the owned disposable clone.
4. Build/install the current app on that clone and prepare synthetic data with sales, products and unfinished examples.
5. Verify the fixture language, currency, simulator mode and available snapshots. Do not rely on archive names alone.
6. Capture representative Home, sale, checkout, result and history screens, including a keyboard-open state.
7. Record device dimensions, insets, locale, app configuration and exact reproduction steps alongside the screenshots.

Keep evidence and a resumable `HANDOFF.md` under ignored `build/ui-review/`. Record important decisions in this plan or
commit descriptions so they survive cleanup of build artifacts. Do not put real credentials in any review artifact.

Acceptance: the app runs reproducibly at AMS1 and S1F2 sizes; the review method and evidence locations are documented;
another agent can reproduce the baseline without touching merchant data.

## 2. Inspect every major flow

Walk through each flow and capture relevant screens, dialogs, keyboard states and results. Cover ordinary first-open
states rather than only hand-arranged screenshots. Record what works as well as problems.

| Area | Coverage |
| --- | --- |
| First run and setup | Demo onboarding, connect-to-Adyen path, setup gate, Terminal steps, connection results, QR setup |
| Home and navigation | All tiles, Settings access, Back, return from completed and interrupted work |
| Sale | Products, categories, search, custom amounts, barcode entry, cart, quantities, removal |
| Checkout | References, email, card saving and consent, tipping, optional card features, payment choices |
| Outcomes | Processing, approval, decline, cancellation, unknown result, status checks, partial approval |
| Receipts | Automatic delivery, printing, merchant copy, email, supported sharing, preview |
| Pre-authorizations | Creation, capture, amount adjustment, cancellation, receipt tips |
| Refunds | Choose payment, scan receipt, full/by amount/by item, confirmation, result, unavailable actions |
| Other tenders | Cash, split by amount/share/item, change, remaining balance, links, scanned wallets |
| Tokens and gift cards | Lookup, selection, charging, deletion, configured gift-card actions and recovery |
| History and reconciliation | Search, filters, details, unresolved activity, reports, totals, period closure |
| Administration | Products, taxes, currency, payments, receipts/logo, security, simulator, capabilities, transfer |

Inspect optional and market-specific features with appropriate synthetic configurations. Distinguish UI coverage from
real integration verification. Update the handoff checklist with completed, pending and unsupported scenarios.

## 3. Review screen sizes and difficult states

Start with the repository's AMS1 test qualifiers, approximately 320 × 460 dp of usable window space. Also inspect the
documented full terminal-frame configuration, S1F2 sizing and the Pixel. Distinguish physical display size from the
usable app window after system insets. Keep simulator disclosure in normal operation; label demo capture evidence.

For each important screen, check:

- Whether purpose, amount and next action are immediately clear.
- Tap/scroll counts, repeated information and unnecessary decisions.
- Keyboard overlap, focus, validation and preservation of entered values.
- Truncated labels, long product names, large amounts and larger font settings.
- Empty, loading, blocked, failed and uncertain states.
- Touch targets, contrast, accessible labels and Back behavior.
- Japanese and Chinese layouts; all seven languages for changed text and overflow.

Use synthetic fixtures for failure and recovery states. Do not deliberately create uncertain real payments merely to
capture a screen. Do not claim accessibility behavior was verified through screenshots alone.

## 4. Produce a prioritized findings register

For each finding, record its identifier, screen, reproduction steps, screenshot, user impact, proposed change, affected
files and acceptance criteria. Separate confirmed defects from usability judgments.

Prioritize blocked work, substantial friction, confusing interactions and finally polish. Cluster repeated problems by
root cause. Avoid broad shared-component changes until their effects across screens are understood.

Preserve deliberate product decisions, including Home tile order, setup gates, pinned primary actions, receipt action
order and simulator disclosure. Keep confirmations, Manager approval, setup verification, persisted operation identity
and explicit unknown outcomes clear and enforced.

Acceptance: every proposed change has observed evidence and a concrete success condition; the implementation backlog
identifies the first coherent group and its verification scope.

## 5. Implement in separate commit groups

These boundaries are provisional. Split, reorder or omit groups according to audit evidence; they are not instructions
to redesign every listed surface.

| Group | Scope when justified by findings |
| --- | --- |
| A: Shared layout and controls | Repeated spacing, overflow, dialog, keyboard and action-placement problems |
| B: Sale and checkout | Product selection, cart review, form hierarchy, payment-option discovery |
| C: Results and follow-up | Outcome clarity, receipts, history details, refunds, captures, recovery |
| D: Additional payment flows | Cash/split, links, wallets, tokens, gift cards |
| E: Setup and administration | Setup progression, Settings navigation, product/tax editors, transfer |
| F: Final consistency | Remaining localization, accessibility and cross-device issues |

For each group:

1. Choose a small set of related findings and state the intended behavior.
2. Read relevant module rules and owner contracts; follow existing state/callback and LocalDimens patterns.
3. Implement the changes, updating all seven translations together when wording changes.
4. Add focused regression coverage where it protects meaningful behavior or a demonstrated layout failure. Use the
   minimpos-tests skill when adding tests. Avoid tests that merely mirror implementation details.
5. Format after the edit batch, then run focused checks. Use one Gradle invocation at a time per checkout.
6. Rebuild and visually repeat affected flows on AMS1, then S1F2 and relevant Pixel configurations.
7. Compare before/after evidence and run `scripts/dev finish` on the final checkout. Fix findings rather than
   suppressing them. Dependency changes additionally require the documented release build.
8. Commit the completed group using Adam Stiskala <github@adamstiskala.com>, without bot attribution.
9. Update the handoff note with the commit, resolved findings, remaining work and exact validation results.

Refresh merchant documentation screenshots if final changes make them materially inaccurate, following the existing
seven-language capture and website verification workflow. Do not regenerate unrelated assets unnecessarily.

## 6. Validate representative flows against the TEST terminal

After simulator changes stabilize, use a separate TEST setup:

- Terminal: `S1F2-000158204502740`
- Merchant account: `PaymentsMadeEasy_Sydney_AU_TEST`
- Store: none
- Currency: AUD

The user supplied an API credential in the original conversation and intends to rotate it. Enter it through the app's
secret storage path. Never include it in logs, screenshots, commits or handoff notes. If it is unavailable to a later
agent or has been rotated, ask for the current credential rather than searching unrelated files for secrets.

Select TEST before configuring the destination. Follow the normal setup and verification path. Do not provision keys,
change Adyen-side capabilities or alter the terminal configuration simply to obtain screenshots.

Exercise representative payment, receipt, history, refund and pre-authorization interactions where supported.
Coordinate terminal prompts with the user and agree on test amounts before sending financial operations. The user has
an NFC test card resting on the terminal, ready for automatic acceptance. Preserve unresolved operations and recovery
evidence. Record exactly what real behavior was verified and which scenarios remain simulator-only.

## 7. Final verification and handoff

- Repeat the key merchant journeys on the final build; inspect the final rendered UI at both terminal sizes.
- Complete outstanding localization/accessibility checks and affected documentation screenshots.
- Run the final required gate and any additional checks justified by the changes.
- Provide the commit list, before/after evidence, resolved findings, outstanding limitations and final gate result.
- Shut down temporary emulators through the documented workflow unless the user requests that one remain running.
- Leave the checkout and handoff note in a clear state for another agent.

## First implementation session

Begin with step 1, then inspect the major flows in steps 2–4 to select the first evidence-backed implementation group.
App code changes are authorized. Complete that first group with visual reinspection, required checks and a separate
commit, then stop at the clean checkpoint with an updated handoff. Do not attempt every group in the first session.
Real-terminal integration testing belongs to step 6 and is not needed for this first checkpoint.
