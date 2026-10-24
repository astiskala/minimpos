# Adyen capabilities implementation plan

Prepared October 24, 2026. This is the implementation handoff for ten requested features. Read it together with
[AGENTS.md](AGENTS.md), the relevant module instructions, [CONTEXT.md](CONTEXT.md), and
[CONTRIBUTING.md](CONTRIBUTING.md). Repository instructions own general conventions; this file owns this work's scope,
sequence, acceptance criteria, and progress.

## Confirmed product decisions

| Area | Decision |
| --- | --- |
| Architecture | Keep the Android app, existing Terminal/Checkout/Management integrations, and no backend. |
| Delivery | Implement sequentially and commit after each complete feature. Keep ten implementation commits. |
| Tokens | Replace the Home entry and lookup screen named Charge token with Tokens, supporting charge and delete. |
| Gift cards | Add a dedicated screen and Home button between Tokens and Refund. |
| Cashout | Add a dedicated screen and Home button between Refund and History. |
| Refunds | Add unreferenced refunds within the Refund entry flow. |
| Settings | A top-right Settings icon on Home opens main Settings directly, subject to the existing admin lock. |
| Products | Put Products in main Settings as a menu item opening the existing product-management screen. |
| Home | Remove the bottom Products/Settings buttons. Do not add an overflow menu before Settings. |
| Splitting | Support chosen amounts, equal shares, item quantities, and cash tenders; later add gift cards and vouchers. |
| Refund allocation | Automatically propose returns to original tenders; staff review and adjust within refundable balances. |
| Gift-card scope | Full provider-supported lifecycle, including deactivation and gift-card cash-out. |
| Gift-card funding | Support both an in-app card funding payment and staff-confirmed funds received outside the app. |
| Meal vouchers | Brazil and the documented European schemes, with eligibility where the scheme requires it. |
| Protected operations | Cashback, standalone cash-out, and unreferenced refunds start disabled and require a configured Manager PIN. |
| Verification equipment | The user has a physical terminal and an Android Tap to Pay phone for TEST verification. |

One policy remains provisional: the previous draft assumes a configured Manager PIN is also required for token deletion,
gift-card value changes, and cash refunds. The user has not separately confirmed that extension. Use it as the clearly
identified working default; balance inquiries and ordinary gift-card payments remain normal workflows. Do not describe
the extension as an expressly confirmed preference. Record any later answer here and in the affected milestone.

Payments app status-beta enablement and provider/market TEST access are unconfirmed. Having hardware does not establish
enablement or authorize an agent to make real Adyen calls. Follow CONTRIBUTING's explicit-approval rule for real calls.

## Final navigation

Keep the two existing full-width New sale and New pre-authorization tiles. Place these rows below them in this order:

| Left | Right |
| --- | --- |
| Tokens | Gift cards |
| Refund | Cashout |
| History | Reconciliation |

The top-right Settings icon opens main Settings in one tap when already unlocked. Products is a normal Settings menu
item; Back from Products returns to Settings and Back from Settings returns to Home. Preserve admin and Manager lock
semantics independently. Settings must remain reachable before Adyen setup is complete.

Land the Home/Settings/Products relocation in milestone 04, the gift-card tile in 06, and the Cashout tile in 09. Keep
row-major ordering among implemented entries at each checkpoint; do not ship empty or nonfunctional placeholder tiles.
Retain the simulator indication and setup card. Check 320 x 460 dp, large text, and CJK labels; scroll when content
needs more space rather than reducing touch targets or making the primary tiles unusable.

## Execution and resumption protocol

1. Read the progress table, `git status --short`, and recent commits. Inspect existing implementation before assuming a
   milestone is absent. Identify unrelated work and preserve it; do not reset, stash, or commit it as feature work.
2. Establish a known baseline. Use a `codex/` branch when creating one. Inspect attached worktrees before creating a new
   one; avoid sharing a checkout with simultaneous edits or Gradle runs. Run `scripts/dev preflight` once per workspace.
3. Implement the first incomplete milestone. Read only its owners, nearest tests, and relevant current Adyen
   documentation. Use [minimpos-iteration](.agents/skills/minimpos-iteration/SKILL.md) and
   [minimpos-tests](.agents/skills/minimpos-tests/SKILL.md). Expand reads when a contract requires it, not as a routine
   scan.
4. Within the milestone, work in small coherent batches: domain/protocol contracts, durable state/recovery, application
   operations, UI/receipts, then translations/documentation. Run focused tests while editing. Do not commit partial UI,
   temporary scaffolding, or a financial operation that lacks recovery.
5. Add shared infrastructure in the first milestone that actually uses it. Extend it in later milestones. Avoid a
   generic payment-framework rewrite or a separate speculative infrastructure commit.
6. At an edit checkpoint run `scripts/dev format`, then the affected focused checks. Never format concurrently with
   edits. Run one Gradle invocation at a time; batch compatible tasks in one invocation when useful.
7. Finish the feature with `scripts/dev finish`. If dependencies changed, also run
   `scripts/gradle :app:assembleRelease`. Resolve findings without new suppressions, baselines, or disabled rules.
8. Review the complete diff and index, update this file's progress/evidence, and commit only the milestone's changes as
   Adam Stiskala <github@adamstiskala.com>, with no bot attribution. Use `this commit` for its hash until the next
   progress update records the actual hash. Do not amend repeatedly just to insert the commit's own hash.
9. Continue to the next milestone when executing the full plan; do not ask for routine permission between milestones.
   Stop for actual ambiguity or missing authorization, not for ordinary implementation choices. Publishing, pushing,
   releasing, and real financial calls are not implied by the instruction to create local feature commits.
10. On interruption, record the active milestone, completed substeps, exact remaining work, relevant failing
    command/log, schema changes, and external verification gaps below. Resume from that state without replaying
    successful operations or repeating passed checks unless changes or unresolved concerns justify it.

The old root instructions about no Payments app status lookup and the old Home button arrangement are superseded by this
task's explicit scope. Update those narrow statements in milestones 01 and 04 respectively; preserve the other rules.

## Contracts shared by every milestone

- Keep Android API 28 support, `app.minimpos`, current permissions, SDK model ownership, encryption, and the no-backend
  architecture. Add no dependency unless the existing SDK and app infrastructure are insufficient.
- Keep money in `Long` minor units using Adyen currency precision. Use checked arithmetic and deterministic rounding.
  Keep purchase, tax, tip, surcharge, stored value, and cash movement distinguishable throughout accounting.
- Persist each money-changing operation's identity, frozen request facts, original context, and pending state before
  sending. Distinguish not sent, answered, and possibly sent. Missing answers stay unknown; retries preserve identity.
- Keep business operation kind separate from wire message category. An unreferenced refund uses Payment; a referenced
  refund uses Reversal; gift-card management can use StoredValue. Recovery must request the original category.
- Extend the existing operation, repository/event, original-context, and history owners. All new runners must
  participate in concurrency protection, setup changes, process recovery, history retention/purge, receipts, and status
  routing.
- Recheck applicable Manager approval immediately before sending. Keep it separate from admin access. Never silently
  replace an unsupported tender or discard an incompatible selected option.
- Resolve eligibility from documented provider/destination support and verified Adyen store/account facts. Device
  locale, app language, currency alone, and simulator approval do not establish support. Add no separate country
  setting.
- Preserve Adyen receipt fields verbatim. Show asynchronous acceptance as requested unless a documented response
  supplies a conclusive outcome. Customer Area remains authoritative where the app cannot establish final completion.
- Keep raw payment codes, card numbers, gift-card PINs, and recovery secrets out of logs, routes, and plaintext history.
  Prefer terminal-collected card details; protect any indispensable retained sensitive recovery material appropriately.
- For each Room change, allocate the next version at implementation time, generate the schema through the build,
  register a data-preserving migration, and test upgrades preserving unfinished operations. Do not edit released schemas
  or fixtures.
- Update stored-format compatibility tests whenever settings, JSON, or journals change. Update the app, setup helper,
  transfer codecs, and vectors together when transferable settings/catalog fields change.
- Include all seven UI languages, affected merchant guides and website tests, useful KDoc, and deliberate-violation
  ArchUnit checks for new ownership rules. Add domain terms to CONTEXT before naming new modules.
- Reuse existing fake transports and simulator ledgers. A simulated end-to-end flow is required but is separate evidence
  from real Adyen compatibility. Record provider/market coverage and gaps explicitly.

## Milestone 01: Payments app transaction-status recovery

Commit: `feat: recover missing Tap to Pay transaction results`

Read first: `adyen/.../paymentsapp/PaymentsAppTransport.kt`, `client/TerminalClient.kt`, `client/ClientModels.kt`,
`app/.../terminal/Destinations.kt`, `DestinationRules.kt`, and `PaymentsAppBridge.kt`. Here and below, `adyen/...` means
`adyen/src/main/kotlin/app/minimpos/terminal/`; `app/...` means `app/src/main/java/app/minimpos/app/`.

- [x] Verify the current Payments app App Link contract and beta requirements. The review found status support
  introduced in v1.8.0; do not infer Payments app support from direct Mobile SDK support or assume every installed
  version supports it.
- [x] Add actual TransactionStatus dispatch through the encrypted App Link path, retaining validated late-reply
  recovery. Represent unverified/unsupported readiness explicitly and provide an actionable explanation.
- [x] Preserve original POIID, SaleID, ServiceID, message category, environment, and credential context. Handle repeated
  original responses, InProgress, NotFound, malformed/wrong-context answers, and absent callbacks conservatively.
- [x] Update destination capabilities, recovery policy, UI explanation, simulator/fakes, and the obsolete root rule.
- [x] Verify process death, late-answer races, dual-install TEST/LIVE routing, and unsupported versions. There must be
  no second PaymentRequest caused by recovery. A missing callback or unconfirmed NotFound must not silently authorize
  retry.

Start with `scripts/dev test adyen '*PaymentsAppTest'` and `scripts/dev test app '*PaymentsAppBridgeTest'`; add focused
recovery cases, then payment and architecture lanes as needed. Done when status can resolve the original operation and
unsupported installations retain safe recovery behavior. Record beta enablement and physical-phone verification
separately from automated completion.

## Milestone 02: Accessible terminal payments

Commit: `feat: offer audio-guided terminal payments`

Read first: `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`, `ShopperDisplay.kt`, `app/.../payment/Checkout.kt`,
`terminal/CapabilityDiscovery.kt`, and the checkout/settings screens.

- [x] Add a per-payment audio-guidance choice and documented model/software/enablement eligibility, including AMS1.
- [x] Use one owner for composing Base64 SaleToPOIData operations. Avoid overwriting existing tip-operation data.
- [x] Explain incompatible tipping, Giving, and signature flows before sending; resolve the selection explicitly.
  Account for extended payment/PIN interactions without turning a slow accessible flow into a false failure.
- [x] Persist required request facts, simulate accessible behavior, and confirm the option resets appropriately after
  completion/cancellation rather than affecting an unrelated next shopper.
- [x] Test eligibility, outgoing operation data, incompatible selections, timeout/status recovery, cancellation, and UI
  readability. Preserve ordinary payments on destinations that do not offer this feature.

Start with `TerminalClientTest`, `CapabilityReadinessTest`, and focused Checkout tests. Done when an eligible shopper
can complete the accessible flow and every incompatible or unavailable case is explained without a silent override.

## Milestone 03: Explicit surcharge accounting

Commit: `feat: track surcharges throughout payment accounting`

Read first: `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`, `app/.../data/db/Entities.kt`,
`data/repo/SaleEvent.kt`, `history/HistoryAccounting.kt`, `history/RefundablePayment.kt`, and receipt owners.

- [ ] Map TotalFeesAmount and establish documented amount semantics: which response totals include fees and tips.
  Persist surcharge per tender and expose sale aggregates without counting it twice.
- [ ] Preserve historical unknown values as unknown rather than inventing zero fees or rewriting prior totals.
- [ ] Update receipts, detail screens, daily accounting, terminal comparison, and full/partial refund review. Show
  purchase and fee components and cap returns by original tender balances; do not treat cashback as refundable
  merchandise later.
- [ ] Continue using Adyen configuration-based calculation. A dynamic surcharge engine or general surcharge editor is
  outside this milestone. Preserve receipt disclosure and documented restrictions involving DCC and adjustments.
- [ ] Test zero/absent fees, tips plus fees, partial approvals, multiple tenders, refunds, currency precision, and
  migration.

Start with TerminalClient, SaleEvent/repository, HistoryAccounting, RefundablePayment, and receipt tests. Run payment
and database lanes. Done when charged amounts and their components reconcile without double counting and old history
survives.

## Milestone 04: Tokens and direct Settings navigation

Commit: `feat: manage and delete saved payment tokens`

Read first: `adyen/.../checkout/SavedCards.kt`, `app/.../payment/SavedCardLookup.kt`, `SavedCardCharges.kt`,
`feature/sale/SavedCardScreens.kt`, `SavedCardViewModel.kt`, `feature/home/HomeScreen.kt`,
`feature/settings/SettingsScreens.kt`, `ui/navigation/Route.kt`, and `AppNavHost.kt`.

- [ ] Rename the Home/lookup entry to Tokens. Keep the charge action named Charge and preserve the existing dedicated
  charge cart, checkout shortcut, shopper-reference lookup, consent/context checks, and charge eligibility.
- [ ] Add explicit token-level Charge and Delete actions. Deletion must remain available for listed tokens that cannot
  currently be charged. Confirm the specific token and its Adyen-wide effect before deletion.
- [ ] Add Checkout DELETE support and permission readiness, Manager policy, concurrency protection, uncertain-outcome
  handling through a fresh listing, and invalidation of all stale token selections. Preserve historical
  payments/refunds.
- [ ] Add the direct top-right Settings icon. Add Products to main Settings, opening the existing Products route. Remove
  Home's bottom Products/Settings buttons and preserve the Back stack, setup access, and admin locking. Update the root
  Home rule and guides describing navigation.
- [ ] Test success, refusal, missing token, deletion timeout/recheck, changed shopper/account/environment, charge/delete
  races, stale checkout selections, navigation, and admin/Manager separation.

Start with `SavedCardsTest`, `SavedCardLookupTest`, `SavedCardFlowTest`, `SavedCardBackTest`, `CheckoutTokenTest`,
`SettingsStructureTest`, `SettingsCatalogUiTest`, and `SmallScreenHomeTest`. Done when charge/delete both work and an
unlocked Home reaches Settings in one tap, with Products reached from Settings.

## Milestone 05: Split payments by amount, shares, items, and cash

Commit: `feat: split sales across items amounts and cash`

Read first: `app/.../payment/Checkout.kt`, `SaleBook.kt`, `TransactionLifecycle.kt`, `FinancialOperations.kt`,
`data/db/Entities.kt`, `data/repo/SaleEvent.kt`, sale/refund repositories, history/refund/receipt owners, and core
money/cart.

- [ ] Extend existing tender records with the type, frozen requested amount, item/quantity allocation where applicable,
  original context, remaining refundable balance, and complete pending/recovery facts. Audit existing multi-tender
  refunds before extending them; never route the whole return to only the latest card.
- [ ] Add operator-selected amounts, equal shares, and item quantities for immediate sales. Preserve sale-level tax and
  pricing; allocate minor-unit remainders deterministically and show the result. Freeze relevant sale facts after
  collection begins. Item allocation must not allow double payment/refund or silently recalculate the merchant's
  original tax.
- [ ] Record cash received, change, and confirmation locally without a Terminal API call. Introduce the minimal durable
  cash-movement/handover accounting needed by later gift-card and debit cash-out. Full till/shift management is not
  required.
- [ ] Reject overpayment and concurrent/stale starts. Resolve unknown electronic tenders before collecting their
  possibly paid balance. Resume partially paid sales and preserve issuer partial-authorization behavior.
- [ ] Automatically propose refund allocation to original tenders; permit review/adjustment within their balances.
  Persist each refund leg independently, route card legs by original context, and record cash returned without a card
  refund. Reserve pending refund amounts so simultaneous or uncertain returns cannot over-refund a tender.
- [ ] Deliver one sale receipt with a tender breakdown plus required Adyen card receipts. Separate cash in local reports
  and exclude it from terminal-only totals. Test mixed/partial/unknown results and interrupted multi-leg refunds.

Start with Checkout, SaleEvent/repositories, RefundablePayment, HistoryAccounting, and receipt tests, then UI wiring and
database/payment lanes. Done when every paid/refunded minor unit belongs to a tender and survives restart. Introduce
gift-card/voucher tender implementations only in their own later milestones.

## Milestone 06: Full provider-supported gift-card lifecycle

Commit: `feat: manage gift cards and stored value`

Read first: `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`, `transport/WalletMethods.kt`,
`app/.../terminal/WalletOffers.kt`, `CapabilityDiscovery.kt`, operation/repository/history/receipt owners, and Home
routes.

- [ ] Verify the operation matrix for Givex, SVS, Fiserv, Intersolve, and payment-only local gift cards. At review time,
  deactivation is documented for Givex only. Offer only configured, provider-supported actions and entry methods.
- [ ] Add typed SDK requests/responses for balance, activation, load, undo load, redemption, refunds, cash-out, and
  supported deactivation. Separate business intent from Payment/Reversal/StoredValue wire categories and recover with
  the right one.
- [ ] Add durable stored-value records rather than disguising every balance change as a merchandise sale. Include those
  operations in running/recovery/retention/history routing. Retain only safe display identifiers and needed recovery
  facts.
- [ ] Support card-funded activation/load and staff-confirmed external funding with an explicit funding source. Model
  funding and loading as separately persisted linked stages. Unknown funding prevents loading; unknown loading requires
  status recovery. A failed load after confirmed funding needs a visible repair/refund path, not a blind repeated load.
- [ ] Add Gift cards between Tokens and Refund on Home. Include balance and lifecycle actions on its screen, and
  redemption at sale checkout using milestone 05's remaining balance. Include provider-specific referenced/unreferenced
  gift-card refunds here; milestone 10 adds general unreferenced card refunds using the reusable refund intent/recovery
  contracts.
- [ ] Enforce undo-load eligibility, including the documented most-recent-load/24-hour condition. For gift-card
  cash-out, separately persist approved value removal and cash handover; expose interrupted handover for review after
  restart.
- [ ] Extend the simulator with balances and linked funding/value operations. Test provider differences, partial
  redemption, insufficient funds, declines, all interruption boundaries, duplicate starts, refund/void races, and
  migration/accounting.

Start with targeted TerminalClient and new gift-card protocol/operation tests plus existing payment/history/receipt
suites. Run database/payment/architecture and AMS1/CJK UI checks. Done when each offered operation is usable end to end
and a funded-but-unresolved load or payout remains recoverable without double funding, loading, refunding, or handing
over cash.

## Milestone 07: Mexican installments

Commit: `feat: support Mexican credit card installments`

Read first: `adyen/.../client/ClientModels.kt`, `TerminalClient.kt`, `app/.../payment/Checkout.kt`, checkout
screens/settings, tender records, capability discovery, and receipt/history owners.

- [ ] Verify supported Mexican credit/debit routing, issuer requirements, installment choices, and valid combinations.
  Derive readiness from verified merchant/store facts and configuration, not Spanish UI language or currency alone.
- [ ] Add the documented credit/debit and installment request fields using SDK models. Preserve choices with each tender
  so recovery sends the same facts; show returned installment information in details and receipts.
- [ ] Validate combinations with splitting and other checkout features centrally. Explain unavailable choices before
  payment. These are issuer-managed installments; do not create device-scheduled recurring token charges.
- [ ] Test normal debit/credit, allowed/disallowed counts, unsupported issuer outcomes, partial/missing responses,
  original-context recovery, refunds, migrations, and localized checkout.

Start with TerminalClient and Checkout tests and add focused installment cases, then payment/database lanes as
applicable. Done when an eligible merchant can choose the plan and its exact request and result remain associated with
the tender.

## Milestone 08: Brazilian and European meal vouchers

Commit: `feat: support meal voucher payment workflows`

Read first: `adyen/.../transport/WalletMethods.kt`, `client/TerminalClient.kt`, `app/.../terminal/WalletOffers.kt`,
`CapabilityDiscovery.kt`, catalog/pricing and transfer owners, Checkout, tenders, refunds, and receipt/history owners.

- [ ] Build a documented per-market/provider behavior table: discovery, onboarding, terminal-managed choices, required
  request fields, product eligibility, amount limits, partial payments, and refund support. Share a protocol path where
  schemes actually share behavior; do not assume every European scheme uses the same one.
- [ ] Reuse Adyen's terminal-managed flow where sufficient. Add explicit voucher intent/selection only where needed for
  the operator workflow, including the documented Brazilian account-type selection when using app-controlled selection.
- [ ] Where required, add product eligibility and calculate the eligible subtotal without changing saved product text or
  tax. Handle custom items explicitly rather than assuming they qualify. Update catalog import/export, setup transfers,
  helper codecs, and vectors together for any new transferable fields.
- [ ] Collect only the eligible amount from the voucher and use split tenders for the remainder. Enforce scheme-specific
  change/refund restrictions; do not convert nonrefundable voucher value into cash or promise unsupported returns.
- [ ] Test eligible/ineligible/mixed baskets, limits, partial approvals, remaining balance, provider rejection,
  uncertain results, correct refund routes, transfer round-trips, and data-preserving migrations.

Start with wallet/capability, Checkout/pricing, catalog/transfer, and history/refund tests. Run payment, database, QR,
localization, and affected website checks as applicable. Done when each offered scheme has explicit behavior and
evidence; unverified providers are documented rather than inferred from one generic simulator success.

## Milestone 09: Cashback and standalone Cashout

Commit: `feat: support cashback and standalone cashout`

Read first: the established cash-movement contracts from 05/06, `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`,
`app/.../payment/Checkout.kt`, financial-operation and Manager owners, capability discovery, Home, receipts, and
history.

- [ ] Add checkout cashback and a dedicated Cashout screen between Refund and History. Both start disabled and require a
  configured Manager PIN, checked again at send time. Readiness must distinguish with-purchase and standalone support.
- [ ] Build requests and parse returned CashBackAmount using documented SDK fields. Persist purchase, requested/approved
  cashback, total card charge, and context separately. Cash disbursed is not merchandise revenue or taxable sale value.
- [ ] Treat the response's cashback amount as authoritative. Correctly handle a purchase approved with cashback refused
  and any documented partial result; do not misread that as an unpaid merchandise balance or automatically pay out cash.
- [ ] Separate terminal approval from the operator's recorded cash handover. Reuse the durable payout state from
  gift-card cash-out. Restart/reprinting/status checks must not automatically imply another handover.
- [ ] Apply market/card/destination and combination restrictions, local amount limits, receipts, cash reports, and
  terminal reconciliation. Standalone eligibility must not be inferred from purchase-linked cashback support.
- [ ] Test approval, denial, partial outcomes, unknown answers, changed setup, repeated taps, PIN expiry, cash handover
  interruption, reports, and the final Home layout.

Start with TerminalClient, Checkout, cash-movement, history/receipt, and Manager tests, then payment/database and
compact UI lanes. Done when the card charge and cash handed out can be independently reconciled and recovered.

## Milestone 10: Unreferenced card refunds within Refund

Commit: `feat: issue unreferenced refunds from the refund screen`

Read first: `app/.../feature/refund/RefundScreens.kt`, `RefundViewModels.kt`, `history/RefundablePayment.kt`,
`payment/RefundBook.kt`, `TransactionLifecycle.kt`, `FinancialOperations.kt`, refund records/repository, and
TerminalClient.

- [ ] Make the Refund entry offer receipt scanning, selecting a stored payment, and Refund without receipt. Keep
  existing referenced-refund detail flows and their eligibility checks. The new flow starts disabled and requires a
  configured Manager PIN, an operational reason, an amount review, and approval recheck before sending.
- [ ] Reuse/extend explicit refund intents introduced for gift cards. Original-payment IDs/timestamps are genuinely
  optional for an unreferenced refund; do not use empty/fake original facts or imply verification against a nonexistent
  original.
- [ ] Send PaymentRequest with PaymentType Refund while retaining refund business/history semantics. Recover against the
  original Payment category. Preserve permitted synchronous confirmation, asynchronous requested, refused, and unknown
  outcomes correctly; a successful request alone does not establish final asynchronous completion.
- [ ] Gate by documented country, scheme, destination, and account configuration, including mobile support where
  verified. At review time Adyen excludes Brazil, Mexico, Australia, Malaysia, Singapore, Hong Kong, and India, and
  excludes QR wallet unreferenced refunds. Revalidate this list. Provider-specific gift-card rules must remain distinct.
- [ ] Support local limits and guide merchants to Adyen's required enablement/limits. Do not claim the app can set an
  Adyen limit through an endpoint that does not support it. Implement permitted cancellation of delayed requests where
  documented; distinguish request-status recovery from final refund settlement.
- [ ] Test no original sale, known original context where relevant, country/scheme denial, PIN expiry, double
  submission, Payment-category recovery, accepted versus confirmed results, retained unknown operations, migration, and
  receipts.

Start with TerminalClient, RefundablePayment, RefundBook/repositories, operation recovery, capability, and Refund UI
tests. Run payment/database/architecture and localization checks. Done when the Refund entry supports both refund types
without weakening existing referenced-refund protections or misreporting asynchronous completion.

## Verification and commit checklist

Apply this checklist to each milestone; do not defer cross-cutting correctness to milestone 10.

- [ ] Focused contract tests cover answered/not-sent/possibly-sent, stable identity, changed original context,
  applicable Manager approval, concurrency, restart, and stale UI/request facts.
- [ ] Any schema/stored-format/transfer changes include their migrations, compatibility checks, and producer/consumer
  tests.
- [ ] Simulator, history, totals, receipts, setup readiness, retention/purge, and seven-language UI/docs are coherent.
- [ ] Relevant architecture checks and type-resolved detekt pass. Use the smallest focused lanes during iteration:

  ```sh
  scripts/dev check architecture
  scripts/dev check payment
  scripts/dev check db
  scripts/dev check localization
  scripts/dev check qr
  scripts/dev check website
  ```

- [ ] Exercise affected UI through existing recording Compose tests, especially `SmallScreenHomeTest`,
  `SmallScreenTest`, and `LocalizedUiTest`. Use the docs-screenshots skill only if capturing emulator/docs images;
  preserve demo disclosures.
- [ ] `scripts/dev finish` passes on this feature's final checkout; release build passes if dependencies changed.
- [ ] Review staged diff for unrelated files, unsafe request/card data, generated-file mistakes, and missing
  documentation.
- [ ] Update progress and verification evidence, commit with the specified author, and continue to the next milestone.

Real-Adyen verification has its own evidence column. Obtain the required explicit authorization before real calls and
use the physical TEST terminal/phone and correctly configured TEST merchant/provider accounts. For each feature verify
one ordinary completion, a refusal/cancellation, missing-answer recovery, receipts/history, and relevant follow-up
actions. Some features require another store country, provider onboarding, hardware model, or beta enablement; record
that gap and avoid claiming tested support. Do not block independent implementation solely because that external access
is absent.

## Progress and resume ledger

Use status `not started`, `in progress`, or `implemented`. Mark implemented in the feature commit once its scope and
automated gate are complete; the status becomes durable when that commit succeeds. Record physical TEST verification
independently, with the provider/destination and evidence location.

| ID | Feature | Status | Implementation commit | Automated evidence | Real-Adyen evidence/gaps |
| --- | --- | --- | --- | --- | --- |
| 01 | Payments app recovery | implemented | `100b1a3` | `scripts/dev finish`; 100% Adyen line coverage | Beta access and physical-phone checks unconfirmed |
| 02 | Accessible payments | implemented | this commit | `scripts/dev finish`; SDK 33 + 28; 100% Adyen line coverage | Physical terminal/software/enablement unconfirmed |
| 03 | Surcharge accounting | not started | - | - | Configured TEST surcharges needed |
| 04 | Tokens and Settings navigation | not started | - | - | Token permissions/setup unconfirmed |
| 05 | Split payments and cash | not started | - | - | Partial-approval scenarios needed |
| 06 | Gift-card lifecycle | not started | - | - | Provider onboarding/TEST cards unconfirmed |
| 07 | Mexican installments | not started | - | - | Eligible Mexican TEST setup unconfirmed |
| 08 | Meal vouchers | not started | - | - | Country/provider TEST setups unconfirmed |
| 09 | Cashback/Cashout | not started | - | - | With-purchase/standalone enablement unconfirmed |
| 10 | Unreferenced card refunds | not started | - | - | Eligible country/account enablement unconfirmed |

Resume note: milestones 01 (`100b1a3`) and 02 (this commit) are implemented on `codex/adyen-capabilities`.
Begin milestone 03 next; milestones 03–10 remain unstarted. Milestone 02 adds database version 17 with a
preserving 16→17 migration and generated schema. There are no settings, transfer, stored-JSON or dependency changes.
The full gate passed. Physical terminal/phone verification, accessibility enablement and Payments app beta access
remain unconfirmed; real calls still require separate authorization.

Milestone 01 evidence:

- Revalidated Adyen's Payments app App Link, transaction-status and release-note pages on October 24, 2026. Status was
  introduced in Payments app v1.8.0. Account/beta enablement remains unverified; installed version never establishes it.
- Full gate passed: `scripts/dev finish`, log `build/gradle-logs/run-hNT3t5/output.log`. This includes both Android SDK
  suites, architecture, lint, localization, website checks and coverage. Adyen line coverage remains 100%.
- Focused protocol/bridge/readiness/routing/guide tests passed. Additional operation-runner recreation, durable
  settlement and changed-account recovery tests passed with Adyen coverage/static checks and guide checks in
  `build/gradle-logs/run-uYaI6L/output.log`.
- Tests cover missing payment/refund callbacks without another financial request, original identifiers and category,
  recreated clients/runners, late-answer races and acknowledgement, old/unknown/upgraded versions, TEST/LIVE routing,
  InProgress, inconclusive NotFound, malformed responses and wrong contexts. Seven-language guidance is updated.
- The gate exposed an existing checkout UI test race; it now waits for the token option after entering the shopper
  reference. No production checkout behavior changed.
- Physical TEST checks are still needed for status beta enablement, installed-version behavior, dual-install routing,
  missing callbacks/process death, InProgress/NotFound and resulting receipts/history. No real Adyen calls, pushes or
  releases were made. Separate explicit authorization is still required for real calls.

Milestone 02 evidence:

- Revalidated Adyen's [POS accessibility requirements](https://docs.adyen.com/point-of-sale/android-terminals/accessibility-mode/accessibility-from-pos)
  and [terminal release notes](https://docs.adyen.com/point-of-sale/firmware-release-notes) on October 24, 2026.
  AMS1, S1F2, S1F2L, S1E2L and S1U2 require 1.117+; M450, P630 and SFO1 require 1.124+. Support must enable
  accessibility on each terminal. Tipping, Giving, signature CVM and Estonian/Latvian/Icelandic/Lithuanian are excluded.
- Model eligibility is advisory, never proof of installed software or enablement. Checkout asks the operator to
  confirm software, Support enablement, language and non-signature card suitability for this terminal per shopper.
  Confirmation explicitly requests the full amount without partial authorization and skips shopper-display prompts;
  incompatible tip/Giving/special-entry choices must be turned off. Links, scanned wallets and tokens require turning
  audio guidance off first.
- One Payment-operation composer preserves normal tip overrides and adds the documented Base64 accessibility variant.
  Accessible payments wait at least ten minutes before status recovery, allowing the default payment/PIN screen
  timeouts; longer Support-configured timeouts can still leave an unknown result. Recovery never aborts or resends.
- The choice is consumed when an attempt starts, with stale-snapshot protection; cancellation or an unknown result
  cannot carry it into an unrelated shopper's attempt. A durable sale flag retains the original request fact, alongside
  the existing ServiceID, Payment category, amount and original context. Schema 17 is generated by Room; older data
  migrates with audio guidance false. No settings/transfer/stored-JSON format changes are needed.
- Simulator tests exercise guidance while waiting, cancellation, signature refusal, missing-answer recovery and reset.
  Simulator mode produces no audio and establishes no hardware support. Seven-language checkout, capability guidance
  and Using Mini mPOS sections explain the workflow and limitations.
- Focused architecture, all Adyen tests, payment/history/receipt tests, database upgrade/repository tests,
  localization/CJK UI, AMS1 layouts, app/Adyen type-resolved detekt and website checks passed; log
  `build/gradle-logs/run-6XUh1y/output.log`. Preflight passed (JDK 21, SDK 37, build tools and Chrome).
- Full gate passed: `scripts/dev finish`, log `build/gradle-logs/run-k8gbuE/output.log`. This includes SDK 33 and 28,
  architecture/dead-code checks, lint, detekt, Dokka, localization, website and coverage. Adyen line coverage is 100%.
- Final focused protocol, checkout, durable recovery, capability, AMS1/CJK layout and dead-code tests passed, including
  SDK 28 UI cases and static/website checks: `build/gradle-logs/run-XcFj0N/output.log`. Busy starts restore the audio
  choice only while its originating session revision is current; an accepted attempt consumes it once.
- The gate exposed an existing SDK 28 AppFlowTest race: it now waits for the charge amount after adding the item,
  the token option after entering the shopper reference and the enabled Pay action. Both SDK suites passed focused
  AppFlowTest checks (`build/gradle-logs/run-kjQM36/output.log`) and the full gate. No ordinary payment behavior changed.
- Physical TEST completion/refusal/cancellation, long PIN interactions, Support enablement, actual software/language
  behavior, missing-answer recovery and receipt/history checks remain unverified on all listed models, including AMS1.
  Milestone 01's physical-phone and Payments app beta gaps remain open. No real Adyen calls, pushes or releases made.

## Adyen source map

These are starting points checked during the review, not frozen API contracts. Revalidate the relevant page when its
milestone begins, especially beta availability, software versions, supported markets, and provider operation matrices.
Prefer official documentation and the pinned SDK's models over secondary examples.

- [Payments app transaction
  status](https://docs.adyen.com/point-of-sale/basic-tapi-integration/verify-transaction-status)
- [Release notes](https://docs.adyen.com/point-of-sale/firmware-release-notes)
- [Payments app integration](https://docs.adyen.com/point-of-sale/mobile-android/build/payments-app)
- [Accessible
  payments](https://docs.adyen.com/point-of-sale/android-terminals/accessibility-mode/accessibility-from-pos)
- [Surcharge](https://docs.adyen.com/point-of-sale/surcharge)
- [Configuration-based surcharge](https://docs.adyen.com/point-of-sale/surcharge/configuration)
- [Token management](https://docs.adyen.com/online-payments/tokenization/managing-tokens)
- [Partial authorizations](https://docs.adyen.com/point-of-sale/partial-authorizations)
- [Gift-card lifecycle and
  providers](https://docs.adyen.com/point-of-sale/alternative-payment-methods/gift-cards-terminal-api)
- [Undo a gift-card load](https://docs.adyen.com/point-of-sale/alternative-payment-methods/gift-cards-terminal-api/void)
- [Gift-card
  refunds](https://docs.adyen.com/point-of-sale/alternative-payment-methods/gift-cards-terminal-api/refund-gift-cards)
- [Mexican installments](https://docs.adyen.com/point-of-sale/installments/mexico-installments)
- [Brazilian meal vouchers](https://docs.adyen.com/point-of-sale/alternative-payment-methods/brazil-vouchers)
- [European meal vouchers](https://docs.adyen.com/industries/markets/europe-meal-vouchers)
- [Cashback and cash-out](https://docs.adyen.com/point-of-sale/cashback-and-cash-out)
- [Unreferenced refunds](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/unreferenced/)
