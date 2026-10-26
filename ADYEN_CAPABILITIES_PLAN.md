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

- [x] Map TotalFeesAmount and establish documented amount semantics: which response totals include fees and tips.
  Persist surcharge per tender and expose sale aggregates without counting it twice.
- [x] Preserve historical unknown values as unknown rather than inventing zero fees or rewriting prior totals.
- [x] Update receipts, detail screens, daily accounting, terminal comparison, and full/partial refund review. Show
  purchase and fee components and cap returns by original tender balances; do not treat cashback as refundable
  merchandise later.
- [x] Continue using Adyen configuration-based calculation. A dynamic surcharge engine or general surcharge editor is
  outside this milestone. Preserve receipt disclosure and documented restrictions involving DCC and adjustments.
- [x] Test zero/absent fees, tips plus fees, partial approvals, multiple tenders, refunds, currency precision, and
  migration.

Start with TerminalClient, SaleEvent/repository, HistoryAccounting, RefundablePayment, and receipt tests. Run payment
and database lanes. Done when charged amounts and their components reconcile without double counting and old history
survives.

## Milestone 04: Tokens and direct Settings navigation

Commit: `feat: manage and delete saved payment tokens`

Read first: `adyen/.../checkout/SavedCards.kt`, `app/.../payment/SavedCardLookup.kt`, `SavedCardCharges.kt`,
`feature/sale/SavedCardScreens.kt`, `SavedCardViewModel.kt`, `feature/home/HomeScreen.kt`,
`feature/settings/SettingsScreens.kt`, `ui/navigation/Route.kt`, and `AppNavHost.kt`.

- [x] Rename the Home/lookup entry to Tokens. Keep the charge action named Charge and preserve the existing dedicated
  charge cart, checkout shortcut, shopper-reference lookup, consent/context checks, and charge eligibility.
- [x] Add explicit token-level Charge and Delete actions. Deletion must remain available for listed tokens that cannot
  currently be charged. Confirm the specific token and its Adyen-wide effect before deletion.
- [x] Add Checkout DELETE support and permission readiness, Manager policy, concurrency protection, uncertain-outcome
  handling through a fresh listing, and invalidation of all stale token selections. Preserve historical
  payments/refunds.
- [x] Add the direct top-right Settings icon. Add Products to main Settings, opening the existing Products route. Remove
  Home's bottom Products/Settings buttons and preserve the Back stack, setup access, and admin locking. Update the root
  Home rule and guides describing navigation.
- [x] Test success, refusal, missing token, deletion timeout/recheck, changed shopper/account/environment, charge/delete
  races, stale checkout selections, navigation, and admin/Manager separation.

Start with `SavedCardsTest`, `SavedCardLookupTest`, `SavedCardFlowTest`, `SavedCardBackTest`, `CheckoutTokenTest`,
`SettingsStructureTest`, `SettingsCatalogUiTest`, and `SmallScreenHomeTest`. Done when charge/delete both work and an
unlocked Home reaches Settings in one tap, with Products reached from Settings.

## Milestone 05: Split payments by amount, shares, items, and cash

Commit: `feat: split sales across items amounts and cash`

Read first: `app/.../payment/Checkout.kt`, `SaleBook.kt`, `TransactionLifecycle.kt`, `FinancialOperations.kt`,
`data/db/Entities.kt`, `data/repo/SaleEvent.kt`, sale/refund repositories, history/refund/receipt owners, and core
money/cart.

- [x] Extend existing tender records with the type, frozen requested amount, item/quantity allocation where applicable,
  original context, remaining refundable balance, and complete pending/recovery facts. Audit existing multi-tender
  refunds before extending them; never route the whole return to only the latest card.
- [x] Add operator-selected amounts, equal shares, and item quantities for immediate sales. Preserve sale-level tax and
  pricing; allocate minor-unit remainders deterministically and show the result. Freeze relevant sale facts after
  collection begins. Item allocation must not allow double payment/refund or silently recalculate the merchant's
  original tax.
- [x] Record cash received, change, and confirmation locally without a Terminal API call. Introduce the minimal durable
  cash-movement/handover accounting needed by later gift-card and debit cash-out. Full till/shift management is not
  required.
- [x] Reject overpayment and concurrent/stale starts. Resolve unknown electronic tenders before collecting their
  possibly paid balance. Resume partially paid sales and preserve issuer partial-authorization behavior.
- [x] Automatically propose refund allocation to original tenders; permit review/adjustment within their balances.
  Persist each refund leg independently, route card legs by original context, and record cash returned without a card
  refund. Reserve pending refund amounts so simultaneous or uncertain returns cannot over-refund a tender.
- [x] Deliver one sale receipt with a tender breakdown plus required Adyen card receipts. Separate cash in local reports
  and exclude it from terminal-only totals. Test mixed/partial/unknown results and interrupted multi-leg refunds.

Start with Checkout, SaleEvent/repositories, RefundablePayment, HistoryAccounting, and receipt tests, then UI wiring and
database/payment lanes. Done when every paid/refunded minor unit belongs to a tender and survives restart. Introduce
gift-card/voucher tender implementations only in their own later milestones.

## Milestone 06: Full provider-supported gift-card lifecycle

Commit: `feat: manage gift cards and stored value`

Read first: `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`, `transport/WalletMethods.kt`,
`app/.../terminal/WalletOffers.kt`, `CapabilityDiscovery.kt`, operation/repository/history/receipt owners, and Home
routes.

- [x] Verify the operation matrix for Givex, SVS, Fiserv, Intersolve, and payment-only local gift cards. At review time,
  deactivation is documented for Givex only. Offer only configured, provider-supported actions and entry methods.
- [x] Add typed SDK requests/responses for balance, activation, load, undo load, redemption, refunds, cash-out, and
  supported deactivation. Separate business intent from Payment/Reversal/StoredValue wire categories and recover with
  the right one.
- [x] Add durable stored-value records rather than disguising every balance change as a merchandise sale. Include those
  operations in running/recovery/retention/history routing. Retain only safe display identifiers and needed recovery
  facts.
- [x] Support card-funded activation/load and staff-confirmed external funding with an explicit funding source. Model
  funding and loading as separately persisted linked stages. Unknown funding prevents loading; unknown loading requires
  status recovery. A failed load after confirmed funding needs a visible repair/refund path, not a blind repeated load.
- [x] Add Gift cards between Tokens and Refund on Home. Include balance and lifecycle actions on its screen, and
  redemption at sale checkout using milestone 05's remaining balance. Include provider-specific referenced/unreferenced
  gift-card refunds here; milestone 10 adds general unreferenced card refunds using the reusable refund intent/recovery
  contracts.
- [x] Enforce undo-load eligibility, including the documented most-recent-load/24-hour condition. For gift-card
  cash-out, separately persist approved value removal and cash handover; expose interrupted handover for review after
  restart.
- [x] Extend the simulator with balances and linked funding/value operations. Test provider differences, partial
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

- [x] Verify supported Mexican credit/debit routing, issuer requirements, installment choices, and valid combinations.
  Derive readiness from verified merchant/store facts and configuration, not Spanish UI language or currency alone.
- [x] Add the documented credit/debit and installment request fields using SDK models. Preserve choices with each tender
  so recovery sends the same facts; show returned installment information in details and receipts.
- [x] Validate combinations with splitting and other checkout features centrally. Explain unavailable choices before
  payment. These are issuer-managed installments; do not create device-scheduled recurring token charges.
- [x] Test normal debit/credit, allowed/disallowed counts, unsupported issuer outcomes, partial/missing responses,
  original-context recovery, refunds, migrations, and localized checkout.

Start with TerminalClient and Checkout tests and add focused installment cases, then payment/database lanes as
applicable. Done when an eligible merchant can choose the plan and its exact request and result remain associated with
the tender.

## Milestone 08: Brazilian and European meal vouchers

Commit: `feat: support meal voucher payment workflows`

Read first: `adyen/.../transport/WalletMethods.kt`, `client/TerminalClient.kt`, `app/.../terminal/WalletOffers.kt`,
`CapabilityDiscovery.kt`, catalog/pricing and transfer owners, Checkout, tenders, refunds, and receipt/history owners.

- [x] Build a documented per-market/provider behavior table: discovery, onboarding, terminal-managed choices, required
  request fields, product eligibility, amount limits, partial payments, and refund support. Share a protocol path where
  schemes actually share behavior; do not assume every European scheme uses the same one.
- [x] Reuse Adyen's terminal-managed flow where sufficient. Add explicit voucher intent/selection only where needed for
  the operator workflow, including the documented Brazilian account-type selection when using app-controlled selection.
- [x] Where required, add product eligibility and calculate the eligible subtotal without changing saved product text or
  tax. Handle custom items explicitly rather than assuming they qualify. Update catalog import/export, setup transfers,
  helper codecs, and vectors together for any new transferable fields.
- [x] Collect only the eligible amount from the voucher and use split tenders for the remainder. Enforce scheme-specific
  change/refund restrictions; do not convert nonrefundable voucher value into cash or promise unsupported returns.
- [x] Test eligible/ineligible/mixed baskets, limits, partial approvals, remaining balance, provider rejection,
  uncertain results, correct refund routes, transfer round-trips, and data-preserving migrations.

Start with wallet/capability, Checkout/pricing, catalog/transfer, and history/refund tests. Run payment, database, QR,
localization, and affected website checks as applicable. Done when each offered scheme has explicit behavior and
evidence; unverified providers are documented rather than inferred from one generic simulator success.

## Milestone 09: Cashback and standalone Cashout

Commit: `feat: support cashback and standalone cashout`

Read first: the established cash-movement contracts from 05/06, `adyen/.../client/TerminalClient.kt`, `ClientModels.kt`,
`app/.../payment/Checkout.kt`, financial-operation and Manager owners, capability discovery, Home, receipts, and
history.

- [x] Add checkout cashback and a dedicated Cashout screen between Refund and History. Both start disabled and require a
  configured Manager PIN, checked again at send time. Readiness must distinguish with-purchase and standalone support.
- [x] Build requests and parse returned CashBackAmount using documented SDK fields. Persist purchase, requested/approved
  cashback, total card charge, and context separately. Cash disbursed is not merchandise revenue or taxable sale value.
- [x] Treat the response's cashback amount as authoritative. Correctly handle a purchase approved with cashback refused
  and any documented partial result; do not misread that as an unpaid merchandise balance or automatically pay out cash.
- [x] Separate terminal approval from the operator's recorded cash handover. Reuse the durable payout state from
  gift-card cash-out. Restart/reprinting/status checks must not automatically imply another handover.
- [x] Apply market/card/destination and combination restrictions, local amount limits, receipts, cash reports, and
  terminal reconciliation. Standalone eligibility must not be inferred from purchase-linked cashback support.
- [x] Test approval, denial, partial outcomes, unknown answers, changed setup, repeated taps, PIN expiry, cash handover
  interruption, reports, and the final Home layout.

Start with TerminalClient, Checkout, cash-movement, history/receipt, and Manager tests, then payment/database and
compact UI lanes. Done when the card charge and cash handed out can be independently reconciled and recovered.

## Milestone 10: Unreferenced card refunds within Refund

Commit: `feat: issue unreferenced refunds from the refund screen`

Read first: `app/.../feature/refund/RefundScreens.kt`, `RefundViewModels.kt`, `history/RefundablePayment.kt`,
`payment/RefundBook.kt`, `TransactionLifecycle.kt`, `FinancialOperations.kt`, refund records/repository, and
TerminalClient.

- [x] Make the Refund entry offer receipt scanning, selecting a stored payment, and Refund without receipt. Keep
  existing referenced-refund detail flows and their eligibility checks. The new flow starts disabled and requires a
  configured Manager PIN, an operational reason, an amount review, and approval recheck before sending.
- [x] Reuse/extend explicit refund intents introduced for gift cards. Original-payment IDs/timestamps are genuinely
  optional for an unreferenced refund; do not use empty/fake original facts or imply verification against a nonexistent
  original.
- [x] Send PaymentRequest with PaymentType Refund while retaining refund business/history semantics. Recover against the
  original Payment category. Preserve permitted synchronous confirmation, asynchronous requested, refused, and unknown
  outcomes correctly; a successful request alone does not establish final asynchronous completion.
- [x] Gate by documented country, scheme, destination, and account configuration, including mobile support where
  verified. At review time Adyen excludes Brazil, Mexico, Australia, Malaysia, Singapore, Hong Kong, and India, and
  excludes QR wallet unreferenced refunds. Revalidate this list. Provider-specific gift-card rules must remain distinct.
- [x] Support local limits and guide merchants to Adyen's required enablement/limits. Do not claim the app can set an
  Adyen limit through an endpoint that does not support it. Implement permitted cancellation of delayed requests where
  documented; distinguish request-status recovery from final refund settlement.
- [x] Test no original sale, known original context where relevant, country/scheme denial, PIN expiry, double
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
| 02 | Accessible payments | implemented | `c8752cc` | `scripts/dev finish`; SDK 33 + 28; 100% Adyen line coverage | Physical terminal/software/enablement unconfirmed |
| 03 | Surcharge accounting | implemented | `c62d133` | `scripts/dev finish`; SDK 33 + 28; schema 18 | Configured TEST surcharge/refund checks unconfirmed |
| 04 | Tokens and Settings navigation | implemented | `3f1c712` | `scripts/dev finish`; SDK 33 + 28; schema 18 unchanged | TEST roles, deletion and propagation unconfirmed |
| 05 | Split payments and cash | implemented | `d208036` | `scripts/dev finish`; SDK 33 + 28; schema 19 | Partial-approval, linking and multi-leg refund checks unconfirmed |
| 06 | Gift-card lifecycle | implemented | `807c62e` | `scripts/dev finish`; SDK 33 + 28; schema 20 | Provider onboarding/TEST cards, StoredValue recovery unconfirmed |
| 07 | Mexican installments | implemented | `d18a349` | `scripts/dev finish`; SDK 33 + 28; schema 21 | Mexican issuer/local/cloud/receipt/refund TEST checks unconfirmed |
| 08 | Meal vouchers | implemented | `e793d80` | `scripts/dev finish`; SDK 33 + 28; schema 22 | Market/issuer onboarding, voucher TEST cards, refunds and recovery unconfirmed |
| 09 | Cashback/Cashout | implemented | `0871263` | `scripts/dev finish`; SDK 33 + 28; schema 23 | With-purchase/standalone enablement unconfirmed |
| 10 | Unreferenced card refunds | implemented | this commit | `scripts/dev finish`; SDK 33 + 28; schema 24 | Store enablement, limits, delay/cancel and recovery unconfirmed |

Resume note: all ten milestones are implemented on `codex/adyen-capabilities`, with 09 at `0871263` (full hash
`08712639`) and 10 in this commit. Stop after 10. The database is version 24: `refunds` was rebuilt so an unreferenced
refund's original transaction ID and timestamp are genuinely null, and gained the unreferenced flag, frozen reason,
verbatim `acquirerResponseCode` and the delayed-refund cancellation identity/status, with data-preserving
`MIGRATION_23_24` and a build-generated schema. Released schemas and stored fixtures are retained; the stored-format
snapshot gained `PaymentSettings.unreferencedRefunds`. No dependencies changed. Preflight and `scripts/dev finish` passed;
the complete gate log is `build/gradle-logs/run-xTti2b/output.log`, including SDK 33 and SDK 28, architecture, localization,
website, lint, detekt and coverage. Core and Adyen retain 100% line coverage. All real-Adyen gaps remain open. No real
calls, pushes, publications or releases were made or authorized.

Milestone 10 protocol and market evidence (October 26, 2026):

- Full gate passed: `scripts/dev finish`, `build/gradle-logs/run-xTti2b/output.log`, including both SDK suites and
  coverage. Focused Adyen, payment, database, architecture, localization, AMS1/CJK UI and website lanes passed during
  iteration. No thresholds, suppressions, baselines or exclusions were added; detekt findings were fixed by extracting
  helpers. Dependencies did not change; the release R8 rules now also keep the Jackson members of the Classic API
  `com.adyen.model.payment` models, and `AdyenModelR8Test` shrinks and round-trips both cancellation models.
- Rechecked [unreferenced refunds](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/unreferenced/),
  [cancel an unreferenced refund](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/cancel-unreferenced),
  the [mobile support matrix](https://docs.adyen.com/point-of-sale/ipp-mobile/) and the
  [Payments app integration](https://docs.adyen.com/point-of-sale/mobile-android/build/payments-app). The excluded
  markets are unchanged: Brazil, Mexico, Australia, Malaysia, Singapore, Hong Kong and India; QR-code wallets (Alipay,
  WeChat Pay) are excluded and must use referenced refunds; some MCCs are excluded without a published list. The
  behavior table is:

  | Market / card / destination | App behavior |
  | --- | --- |
  | Verified store in an excluded country | Blocked before review (`UnreferencedRefundBlock.COUNTRY`), even when enabled |
  | Other verified store, card presented on a local or cloud payment terminal | Offered after explicit enablement, local limit and configured Manager PIN |
  | Excluded MCC or card scheme refusal | Not predicted; Adyen's refusal is stored verbatim as declined |
  | QR-code wallets | Not offered: the request asks the shopper to present a card; wallets refund by reference |
  | Payments app / Tap to Pay | Documented by Adyen with enablement, but not offered: this app verifies no assigned store country there |
  | Simulator | Explicit offline demo with asynchronous answers; establishes no market, scheme or account support |
  | Gift cards | Unchanged provider-specific `GiftCardUse.REFUND` path from milestone 06 |

- Exact request (pinned SDK 43.1.0 nexo models, no handwritten wire model): `MessageCategory=Payment`,
  `PaymentRequest.SaleData.SaleTransactionID` (`R-…` reference, UTC timestamp), `PaymentTransaction.AmountsReq`
  (`Currency`, `RequestedAmount`) and `PaymentData.PaymentType=Refund`, with application info and the `ReceiptHandler`
  tender option only; `PaymentParams.unreferencedRefund` refuses every other card feature, shopper reference and
  `SaleReferenceID`. The answer's `Response.Result=Success` is stored as **requested**: Adyen documents asynchronous
  processing and the `REFUND_WITH_DATA` webhook as the outcome, which this backend-free app shows only as Customer Area
  guidance. `acquirerResponseCode` (sent only when the scheme and country process synchronously) is kept verbatim;
  `Approved` is shown as **Refund approved**, still not final settlement. Refusals are declined, unsent requests failed
  and missing answers unknown.
- Enablement and limits: Adyen enables unreferenced refunds per store/terminal (Customer Area or Management
  `refunds.unreferenced.enableUnreferencedRefunds`); the unreferenced refund limit is set only in the Customer Area or by
  Support, and the refund delay only by Support. The app does not call the Management endpoint and claims to set no Adyen
  limit. Merchant confirmation (`PaymentSettings.unreferencedRefunds`) binds enablement, a positive local limit, the
  currency and the Support refund delay to the verified Management store ID/country and payment context; a moved store
  or changed currency clears it. `UnreferencedRefunds` (pure) decides readiness; `StoredPaymentActions` freezes the
  reason, reference and configuration identity; the gateway rechecks the identity, destination and limit at send; the
  lifecycle requires a configured Manager PIN held from confirmation and checked again before sending.
- Durable state: each refund is inserted PENDING with its ServiceID before sending, records the actual payment context,
  settles in place, becomes UNKNOWN at restart and is rechecked only by its own book with `TransactionKind.PAYMENT`
  (status `MessageCategory=Payment`, original ServiceID, original context); the referenced refund book never claims it.
  Original-payment IDs/timestamps are null rather than invented; receipts and details say there is no original.
- Cancellation: Classic Payments API v68 `/voidPendingRefund` (SDK `VoidPendingRefundRequest`/`ModificationResult`),
  `originalReference` = the refund's PSP reference, through the existing `AdyenHttp` transport and Checkout credential.
  `[voidPendingRefund-received]` is stored as cancellation **requested**; `VOID_PENDING_REFUND` is the Customer Area
  outcome. Each request is stored SENDING with `void-<refund ID>` as idempotency key before sending; unknown answers stay
  unresolved and are resent unchanged within the seven-day key window; a later definite rejection cannot overwrite an
  unresolved first request. Offered only with the merchant's delay confirmation, the original context and approval.
  A 403 names the Classic API role; the role is not added to every setup.
- Receipts, history and reports: refund receipts omit an original and surcharge/tip, list "not linked", the reason,
  any acquirer answer and cancellation request, and keep Adyen fields verbatim. History titles them Refund without
  receipt; search finds their references. Day totals and the daily report show them within refunds and separately;
  terminal reconciliation counts them as credits. Unresolved refunds or cancellations remain issues, survive retention
  and trigger environment-switch warnings.
- Automated scope: Adyen request/answer fields, synchronous versus asynchronous answers, missing-answer recovery by
  Payment category, unknown results, invalid combinations, simulator credits and cancellation, Classic API URL/body/
  idempotency/403/odd answers; pure readiness for every excluded market, unverified/changed facts and simulator demo;
  absent original, requested versus approved, interruption/restart routing, retention of unknown operations, changed
  configuration and limit, unconfigured and expired Manager PIN, repeated starts, accounting, cancellation identity and
  context mismatch; 23 → 24 migration with unfinished and Checkout refunds; receipts; AMS1 at 1.5× text, Japanese and
  both Chinese scripts; `DoubleActivationTest`; a new ArchUnit rule with deliberate violations; seven-language guide.
- Real-Adyen verification remains open for store enablement and MCC exclusions, Customer Area limits, local/cloud
  terminal answers per scheme/country, `acquirerResponseCode`, status recovery, receipts, terminal totals, Payments app
  support, the refund delay, `/voidPendingRefund` roles and outcomes. Simulator approval proves none.

Milestone 09 protocol and market evidence (October 26, 2026):

- Full gate passed: `scripts/dev finish`, `build/gradle-logs/run-odUgIC/output.log`, including both SDK suites and
  coverage. The final ledger-only update is checked again before committing. Focused request, settlement, handover,
  migration, receipt, compact UI and website checks also passed during iteration. No thresholds, suppressions,
  baselines or exclusions were added. No release build was needed because dependencies did not change.
- The gate exposed asynchronous Settings navigation assumptions in existing UI tests; they now wait for the target
  section. Checkout tests also wait for validation state and scroll the final Home rows before activating them.
  Cashout with a reported surcharge is still wholly ineligible for merchandise refunds; unreported or inconsistent
  cashback cannot enlarge a refund balance.
- Rechecked [cashback/cash-out](https://docs.adyen.com/point-of-sale/cashback-and-cash-out),
  [partial authorizations](https://docs.adyen.com/point-of-sale/partial-authorizations),
  [transaction status](https://docs.adyen.com/point-of-sale/basic-tapi-integration/verify-transaction-status),
  [referenced refunds](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/referenced/) and
  [terminal release notes](https://docs.adyen.com/point-of-sale/firmware-release-notes). The market matrix is:

  | Verified market/card | With purchase | Standalone cash-out | Destination and enablement |
  | --- | --- | --- | --- |
  | Australia / eftpos debit (AUD) | Yes | Yes | Terminal API physical terminals, local/cloud; Support enablement |
  | Germany / girocard debit (app: EUR) | Yes | No | Same; merchant confirms store/card program |
  | Europe / Visa | Yes | No | Same; explicit store enablement |
  | Europe / Mastercard | Specific countries confirmed by Support | No | Same; no guessed acceptance-country list |
  | Other cards/markets; Payments app/Tap to Pay | Not offered | Not offered | No documented support inferred |
  | Simulator | Explicit offline demo | Explicit eftpos demo | Establishes no real eligibility |

- Shared behavior for the permitted programs (Terminal API, not the standalone terminal menu):

  | Behavior | Exact contract / app restriction |
  | --- | --- |
  | Purchase request | `PaymentRequest.PaymentTransaction.AmountsReq.RequestedAmount` = purchase + cash; `.CashBackAmount` = cash |
  | Standalone request | Same SDK fields, purchase zero; no speculative `PaymentType=CashAdvance` or acquirer extension |
  | Approved cash | `PaymentResponse.PaymentResult.AmountsResp.CashBackAmount`, within `AuthorizedAmount`, is authoritative |
  | Partial / refused cash | Cashback `Partial` approves the purchase without cash; never a merchandise remainder. Omitted cash remains unreported |
  | Limits | No universal numeric amount limit documented; explicit local positive limit, currency-bound, with issuer/store limits still applying |
  | Receipts | Original Adyen fields verbatim; separate request, approved cash, card charge and operator handover; Cashout title and email kind |
  | Daily cash report | Approved cash and physical cash handed over are separate; cash never merchandise sales or tax |
  | Terminal reconciliation | Compare the full card debit with terminal totals; physical cash handover is a local event |
  | Referenced refunds | App excludes all cash from merchandise return balances; no Cashout merchandise or card-cash reversal is offered |

  The cashback page supplies the request/result semantics; the app's local handover and refund restrictions are
  deliberate safeguards, not claims of a documented generic cash reversal. No `AllowPartialAuthorisation` tender option
  is added. No missing component is replaced by the requested amount. Customer Area remains authoritative.
- Release notes identify girocard support in terminal software 1.64 and Visa/Mastercard extension in 1.72; version alone
  proves no account enablement. The [standalone terminal menu](https://docs.adyen.com/point-of-sale/standalone/standalone-use)
  does not offer purchase-free cash-out. That integration is distinct from this app's Terminal API Cashout screen.
  The [905_4 error](https://docs.adyen.com/development-resources/error-codes) mentions eftpos in Australia/New Zealand;
  that error explanation does not establish New Zealand support against the current feature matrix.
- Pinned SDK 43.1.0 inspected with `javap`: `AmountsReq.get/setRequestedAmount`, `get/setCashBackAmount`,
  `AmountsResp.get/setAuthorizedAmount`, `get/setCashBackAmount` use `BigDecimal`. Existing `TerminalClient` parses
  returned cash with its one Adyen-decimal converter; only the request setter was missing. No handwritten wire model
  or dependency change is needed. These are normal `Payment` requests, recovered by original category and ServiceID.
- App contract: explicit configuration is bound to verified Management store ID/country, payment context and currency.
  The European geographic boundary is EEA, Switzerland and the United Kingdom; membership does not establish card
  program acceptance. The [payment-method matrix](https://docs.adyen.com/payment-methods) lists eftpos in AUD.
  The app conservatively offers German girocard cashback in EUR; other currency combinations are not claimed.
  No editable country setting. Purchase-linked and standalone flags start off; a positive local limit and configured
  Manager PIN are required. The operator confirms the eligible card program. Undocumented combinations are blocked
  before payment and retained visibly, never silently dropped. A selected cash request has a whole immediate card
  purchase, no split/cash tender, gift card, voucher, installments, tips, Giving, audio, MOTO/MKE, links, tokens or wallets.
- Cash receipt lines show requested/approved cash, card charge and recorded handover independently, retaining Adyen
  fields verbatim. Daily sales exclude cash; reports separately show approved cash and physical cash handed over on
  the handover date. Terminal reconciliation uses the total card charge. Cash is excluded from referenced merchandise
  refund balances; no standalone cash-out merchandise refund exists. The referenced-refund page documents returning
  purchase value, and does not establish a card-cash reversal facility; the app offers none. Customer Area is authoritative.
- `CashTenders` owns handovers for both card cashback and gift-card cash-out; their shared pure `CashHandover` reading
  keeps terminal approval separate from operator recording. Awaiting/unreported cash survives retention and restart.
  Reprinting/status recovery cannot record handover. Repeated confirmations cannot overwrite its original time.
- Automated regression scope includes exact SDK request/returned cash fields, purchase-only refusal/partials,
  unknown-answer recovery under the original Payment identity, configuration/store/currency changes, mandatory PIN
  expiry checks, repeated activation, one-time handover across restart/history clearing, full-debit reconciliation,
  cash-only surcharge/refund separation, schema 22 → 23 preservation and stored-format compatibility. The AMS1 Home
  and Cashout tests exercise large text, Japanese and both Chinese scripts; receipts and guides cover seven languages.
  Checkout displays the full requested card charge while preserving separate merchandise/tax totals.
- Real-Adyen verification remains open for every market/card/store enablement, minimum/maximum issuer amounts,
  terminal software and local/cloud destinations, purchase-only partials, refused cash, omitted or unusual cash amounts,
  original-identity recovery, receipts, terminal totals and purchase-only referenced refunds.
  Simulator approval proves none.

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

Milestone 03 evidence:

- Revalidated Adyen’s [configuration-based surcharge](https://docs.adyen.com/point-of-sale/surcharge/configuration),
  [surcharge response](https://docs.adyen.com/point-of-sale/surcharge/dynamic) and
  [terminal tipping](https://docs.adyen.com/point-of-sale/tipping/tipping-from-terminal) contracts on October 24, 2026.
  `TotalFeesAmount` is the surcharge component of `AuthorizedAmount`; tips are also included in the authorized
  total. Configuration may charge on purchase plus tip or exclude gratuity. Amounts use Adyen currency precision.
- Each new terminal tender retains its authorized total, reported fee/tip/cashback, request ServiceID, original
  context, processing time, reconciliation period and verbatim card receipts. Sale aggregates never add fees or tips
  to the already-inclusive authorized total. Missing fee components stay unknown, including through migration and
  mixed old/new history. Changed captures keep reported zero fees; positive fee breakdowns are not inferred.
  Cashback is excluded from return balances.
- Full and partial returns review one original card at a time, bounded by that card and the sale. Pending/unknown
  returns reserve their balances. An atomic pre-send balance and prior-return snapshot check records stale requests
  as known not sent. Request components remain frozen for recovery. Amount returns cover purchase, then tip, then fee;
  item returns cover purchase. Multi-card automatic allocation remains milestone 05, as planned.
- Remaining-balance attempts now durably store their own ServiceID and requested amount before sending, retain the
  original context, and recover without a second payment. Repeated answers refresh the known outcome without adding
  amounts or tender rows twice. New partial responses with unreported fees cannot establish a safe purchase remainder;
  collection stops with Customer Area/refund guidance rather than treating the missing fee as zero.
- New tender activity is attributed to its own processing day and reconciliation period through bounded history
  queries without receipt bodies. Legacy tender rows retain prior sale accounting. Daily reports disclose known
  purchase and fee components, returned fees, and unreported fee counts. Receipts retain all original card disclosures.
- Seven-language result/detail/refund text and merchant guides cover the workflow, unknown components, disclosure,
  DCC and adjustment restrictions. Reported surcharged holds cannot be adjusted or increased; configuration-based
  Adyen calculation remains the only real surcharge mechanism. No dynamic engine or surcharge editor was added.
- Simulator and fake tests cover absent/zero fees, tips plus fees, partial approvals, multiple cards, independent
  days/periods, missing answers, recreated recovery, duplicate settlement, stale and partial returns, fee-only
  remainders, cashback exclusions and Adyen currency precision. Upgrade tests preserve version-17 unfinished payments,
  returns, tender identities, audio requests and original amounts. AMS1 English/Japanese/Traditional Chinese review
  tests passed, alongside the existing localized and small-screen checks.
- Preflight passed. Focused history/accounting, schema upgrades, sale events, simulator workflow, architecture and
  type-resolved main/test detekt passed in `build/gradle-logs/run-LUZibu/output.log`. Core coverage and both SDK app-flow
  suites passed focused checks. Shortened hints, localization/CJK, small-screen tests, main/test detekt and Android
  Lint passed on October 25, 2026: `build/gradle-logs/run-Ld964u/output.log`.
- Full gate passed: `scripts/dev finish`, log `build/gradle-logs/run-WKC6Sd/output.log`, on October 25, 2026.
  This includes SDK 33 and 28, migration/repository and payment recovery tests, architecture/dead-code checks, lint,
  detekt, Dokka, localization, website checks and coverage. Core and Adyen line coverage remain 100%.
- The gate exposed existing checkout test races. The pre-authorization test now waits for selected card saving after
  entering the shopper reference; the P630 receipt-tip test waits for its selected option before paying. Result
  assertions identify the header amount independently of the new purchase component. Captured surcharge/receipt-tip
  totals have a dedicated delivery regression test.
- A focused lint run stalled in Kotlin comment traversal. Its thread dump is retained privately at
  `build/gradle-logs/run-k6EGOU/threads.txt`. Restarting the owned daemon resolved it; no checks were suppressed.
- Per the follow-up request, the audio-guidance and receipt-tip switch descriptions are shorter in all seven
  languages and limited to two lines. Full accessibility requirements remain in the confirmation and merchant guide.
- Physical TEST surcharges with tips, partial approvals, full/partial returns, DCC configuration, actual receipt
  disclosure and Customer Area completion remain unverified. Milestones 01–02’s hardware/enablement gaps remain open.
  No real Adyen calls, pushes, publications or releases were made.

Milestone 04 evidence:

- Revalidated Adyen’s [token-management guide](https://docs.adyen.com/online-payments/tokenization/managing-tokens),
  [Checkout DELETE endpoint](https://docs.adyen.com/api-explorer/Checkout/72/delete/storedPaymentMethods/(storedPaymentMethodId))
  and [credential roles](https://docs.adyen.com/development-resources/api-credentials/roles) on October 25, 2026.
  DELETE takes the exact token ID, merchant account and shopper reference, returns HTTP 204 without a response body,
  and prevents future payment requests using that token. The endpoint requires `API tokenise payment details`.
  The pinned SDK 43.1.0 includes `RecurringApi.deleteTokenForStoredPaymentDetails`, returning void; no upgrade or
  handwritten request/response model was needed. Requests retain the existing bounded, no-retry HTTP transport.
- Setup checks and seven-language credential guidance now include the additional token-management role. Older verified
  fingerprints require fresh access checks. No schema, serialized data, transfer or dependency format changed;
  database version remains 18 and milestone 03’s preserving migration is unchanged.
- Tokens offers independent Charge and Delete actions, including deletion of listed tokens supporting another recurring
  model. Confirmation freezes the card summary, token ID, shopper reference, account, environment and listing identity;
  it explains the effect across integrations using that Adyen token. A configured Manager PIN and unexpired approval
  are required at send time under the plan’s provisional working default, not a separately confirmed preference.
  Admin unlock does not approve deletion. Setup gating is checked again when the confirmed action starts.
- Deletion, token charges and their recovery sends share serialization. Management requests participate in the global
  running-operation reading so setup replacement waits. Starting and settling deletion invalidates every explicit and
  checkout lookup; late reads and stale offers cannot restore selections. Confirmed complete history purges release
  the process-only management evidence too. Historical payments, original contexts and PSP-based refunds are unchanged.
- HTTP refusals remain known not deleted. A missing response, uncertain HTTP result or missing-token response triggers
  a complete fresh listing for the original account/environment with current eligible credentials. Absence establishes
  that the token is unavailable; continued presence or failed listing stays unconfirmed and blocks new token charges.
  Check deletion only lists again; it never resends DELETE. Malformed/incomplete or explicitly mismatched listings
  cannot prove absence.
  Credential rotation or SDK-client recreation can still perform this read-only check after original-context validation.
  Management evidence and listings are memory-only: process recreation requires a fresh listing, never replaying
  deletion.
- Home now has Tokens/Refund and History/Reconciliation below both full-width payment tiles. Its top-right Settings
  icon opens main Settings under the existing admin lock; Products opens there and Back returns through Settings to
  Home. Settings/setup remain available before verification. No placeholder tiles or overflow menu were added.
  Seven-language resources, setup/operation guides and navigation/localization tests reflect the change. The docs
  capture driver also reaches Products through Settings; no emulator or merchant data was reset.
- Simulator and fake tests cover success, refusal, missing token, lost DELETE response without transport retry,
  read-only timeout reconciliation, continued presence, stale/late selections, shopper/account/environment/credential
  changes, charge/delete double activation, Manager expiry/admin separation and process recreation. Simulator removal
  survives adapter replacement within its ledger’s process lifetime and preserves earlier charge/refund identities.
  English, Japanese and Traditional Chinese deletion confirmations are exercised at AMS1 size, with scrollable text.
- Preflight passed. Focused protocol, token/Manager, setup, navigation, localization/CJK, app-flow, type-resolved
  main/test detekt and website checks passed in `build/gradle-logs/run-YYzr2B/output.log`.
- Focused token, navigation, localization and receipt-tip view-model checks passed on both SDK 33 and SDK 28 in
  `build/gradle-logs/run-qMrMXm/output.log`. Find waits for the lookup owner’s current shopper reading; the SDK 28
  flow test waits for its enabled action. Receipt-tip tests now close and join their view models before resetting
  Main, fixing an existing teardown race without changing payment behavior or assertions.
- Lint encountered the same Kotlin comment-traversal stall seen in milestone 03. The private thread dump is retained
  at `build/gradle-logs/run-YefyzC/threads.txt`; restarting the owned daemon resolved it. The subsequent full SDK 33
  and 28 suites passed. Lint findings from the removed Home lock label and test-helper visibility were fixed directly.
  No checks were excluded or suppressed. The receipt-print popup test now waits for its asynchronously measured
  preview and enabled Print action before tapping, preventing a layout shift from moving the button out of view.
- Final focused token, stale-selection, rotated-credential/context and popup checks passed on SDK 33 and 28 in
  `build/gradle-logs/run-IKVqbr/output.log`. Pending deletion has its own seven-language readiness message, independent
  of financial-operation retry permission. Existing stored-format and upgrade tests remain valid.
- Full gate passed on October 25, 2026: `scripts/dev finish`, log `build/gradle-logs/run-KtNk3T/output.log`. This includes
  SDK 33 and 28, architecture/dead-code checks, migrations/repositories, payment recovery, lint, main/test detekt,
  Dokka, localization, website/helper checks, shell/workflow/Markdown checks and coverage. Core and Adyen line coverage
  remain 100%. Milestones 01–03, including surcharge accounting and the two-line audio/receipt-tip descriptions,
  remain intact.
- Actual TEST credential-role reporting, DELETE completion/refusals, listing propagation and missing-response
  reconciliation remain unverified with Adyen. Earlier hardware, accessibility, Payments app beta and configured
  surcharge/refund gaps remain open. No real Adyen calls, pushes, publications or releases were made.

Milestone 05 evidence:

- Revalidated Adyen’s [partial authorizations](https://docs.adyen.com/point-of-sale/partial-authorizations),
  [terminal tipping](https://docs.adyen.com/point-of-sale/tipping/tipping-from-terminal),
  [surcharge](https://docs.adyen.com/point-of-sale/surcharge) and
  [referenced refunds](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/referenced) on
  October 25, 2026. `AllowPartialAuthorisation` is a tender option; `Result=Partial` returns a lower `AuthorizedAmount`,
  which also includes any tip; the balance is collected by a further request linked by `SaleReferenceID` or in cash.
  Surcharge pages give no split guidance beyond the adjustment restriction. Referenced refunds allow several partial
  refunds per payment and complete asynchronously. The pinned SDK 43.1.0 already carries every field used; no Adyen
  model, request or dependency changed. Every tender keeps the sale’s merchant reference as `SaleReferenceID`.
- Audit of existing multi-tender refunds: the refund screen made staff pick one card, never proposed the others, and
  item returns were unavailable with more than one card. No path sent a whole return to only the latest card: tender
  rows exist since schema 11 and stored refunds without a tender identity were already refused for multi-card sales.
  Split sales are now read as one return across every original tender.
- Schema 19 adds tender kind, frozen allocation JSON, cash received/change and cash history environment to
  `sale_tenders`, and a cash flag to `refunds`; existing rows stay approved card tenders and card returns. Each card
  tender row is inserted PENDING with its ServiceID, frozen amount and allocation before sending, records its context,
  settles in place and becomes UNKNOWN on interruption. Refundable balances are derived from the refund legs naming each
  tender, including pending and unknown reservations. The allocation format joins the reviewed stored-format snapshot.
- Splits support operator amounts, equal shares (remainders on the first shares, the last share takes what is unpaid
  after a partial approval) and sold item units at their apportioned sold gross; prices and tax never change once the
  cart is frozen into the stored sale. Item tenders are collected without partial authorization so units are paid
  once; other card tenders keep the issuer’s partial approvals. Tipping, donations, audio guidance and MOTO/MKE must be
  turned off before splitting, so no option is dropped silently. `TenderBalance` checks the reviewed paid amount,
  balance, allocation and unresolved tenders inside each storing transaction; stale, excessive, duplicate and
  concurrent starts store nothing, and a stale card start no longer marks its sale unknown.
- Cash tenders record the cash received, change and confirmed amount locally through `CashTenders`, with the history
  environment, never a Terminal API call. Partially paid sales resume from the result, History detail or after restart.
  Unknown card tenders must be resolved first. Only one automatic receipt is delivered, once nothing is due; it lists
  every tender, cash received, change and any balance, plus each card’s verbatim Adyen receipt lines.
- Returns of split sales propose legs across all original tenders (units first to the item tenders that paid them,
  then in proportion to what each can return); staff adjust legs within balances and the total must match. `RefundLegs`
  persists each leg before sending, sends card legs in their original contexts with the approval taken at confirmation,
  then records cash handed back locally, which requires a configured Manager PIN under the plan’s provisional default.
  A leg that is not accepted stops the rest. Daily reports show cash tenders and cash returned separately; terminal
  comparison excludes cash. A new ArchUnit rule with a deliberate-violation check keeps cash recording in its owners.
- Seven-language strings, the Using Mini mPOS split and refund guidance, CONTEXT and module rules are updated. Tests
  cover pure allocation, shares and partial approvals, item double allocation, cash change, stale/excessive/concurrent
  starts, interrupted tenders, proposals and adjusted allocations, cash legs and Manager approval, stale legs,
  accounting/reconciliation, receipts, the 18→19 upgrade and AMS1 English/Japanese/Traditional Chinese split flows.
- Preflight passed. Focused architecture, payment/history/repository/schema tests, detekt, localization and website
  lanes passed. Full gate passed on October 25, 2026: `scripts/dev finish`, log
  `build/gradle-logs/run-mpPTTN/output.log`, including SDK 33 and 28, lint, detekt, coverage and website checks.
- The gate exposed two existing AppFlowTest races: Products→Home now waits for Settings before its second Back, and
  the card-saving payment waits for the switch state before paying. Lint’s synthetic-accessor findings were fixed by
  widening helper visibility to internal; nothing was suppressed.
- Real-Adyen gaps: partial approvals within splits, `SaleReferenceID` linking in the Customer Area, terminal totals
  beside local cash, multi-leg referenced refunds and their Customer Area outcomes remain unverified. Earlier gaps
  remain open. No real Adyen calls, pushes, publications or releases were made.

Milestone 06 evidence:

- Revalidated Adyen’s [gift-card operation matrix](https://docs.adyen.com/point-of-sale/alternative-payment-methods/gift-cards-terminal-api)
  and its activate, load, balance, payment, undo-load, refund and deactivate/cash-out pages, plus
  [transaction status](https://docs.adyen.com/point-of-sale/basic-tapi-integration/verify-transaction-status), on
  October 25, 2026. Givex, SVS, Fiserv (`valuelink`) and Intersolve card types support activation, load, balance,
  undo load, redemption, refunds and cash-out; deactivation (`Unload`, zero amount) is Givex only; local gift cards
  (Denmark, France, the Netherlands, Norway, Sweden, United Kingdom) take payments only. Activation/load/undo/deactivation
  use message category `StoredValue`; balance uses `BalanceInquiry`; redemption, cash-out (`redemptionType=cashback`,
  normally the whole balance, partial only for some Givex cards) and unreferenced refunds (`PaymentType=Refund`) use
  `Payment`; referenced refunds are reversals needing no card details. Undo refers to the most recent load within 24
  hours by `.pspReference`. Status checks document `Payment`, `Reversal` and `StoredValue`. The pinned SDK 43.1.0 models
  every request, `StoredValueResponse` inside repeated status responses and `SaleToAcquirerData.redemptionType`; no
  dependency or handwritten wire model changed.
- Providers are explicit merchant configuration in shared `PaymentSettings.giftCards` (card type for Intersolve and local
  cards, scanned/swiped/keyed entry). Only configured providers’ documented operations are offered; nothing is inferred
  from the simulator, currency or language. Tap to Pay (`DestinationRules.giftCards`) takes no gift-card requests and
  the runner refuses to start there. Scanned numbers stay in a single-use `GiftCardAccount`; only the last four digits
  are stored, never numbers or PINs.
- Schema 20 adds `gift_card_operations` (durable stages linked by group: funding by card or staff-confirmed external
  funds, the value change, repair, cash handover) and `sale_tenders.giftCard`. Business intent (`GiftCardStage`) is
  separate from the stored message category; each stage is stored PENDING before sending, settled in place, made
  UNKNOWN at restart and rechecked with its original category and context. `GiftCardActivity` alone decides what an
  activity needs: unknown funding blocks the value; a value not delivered after confirmed funding offers sending the
  value again (explicitly, never automatically), a referenced refund of the card payment or recording external funds
  given back; undo is offered only for the card’s latest local load within 24 hours. Approved cash-outs store a cash
  handover awaiting staff, confirmed only through `CashTenders` (new ArchUnit rule with deliberate violation); an
  unconfirmed handover survives restart and retention. Activities join running checks, history routing, search,
  retention, environment purges, simulator forgetting, receipts, email, terminal comparison and the daily report as
  stored value, never sales.
- Home has the Gift cards tile between Tokens and Refund (Refund/History, then full-width Reconciliation). The screen
  reads balances without approval; value changes are confirmed, then need a configured Manager PIN (provisional
  default) checked again before sending. Split checkout offers a Gift card tender with partial approval reusing
  `TenderBalance`; redemptions return by reversal, except local cards, which are refunded outside the app. The simulator
  keeps provider-side balances and linked funding/value behavior. `TerminalExchange` now owns message building and
  category-correct recovery for payments, reversals and stored value.
- Seven-language strings and Using Mini mPOS sections, CONTEXT, AGENTS and CONTRIBUTING gaps are updated. Tests cover
  the provider matrix, wire shapes, StoredValue recovery, simulator rules (partial redemption, insufficient funds,
  whole-balance cash-out, Givex-only deactivation, 24-hour latest-load undo, refunds), card and external funding,
  refused activation repair, continue after activation, unknown funding recovery, cash handover after restart, Manager
  approval, duplicate and blocked starts, cancellation, split redemption and its return, local-card exclusion,
  accounting/reconciliation/report rows, the 19→20 upgrade, AMS1 at 1.5× text and Japanese/Chinese AMS1 screens.
- Full gate passed on October 26, 2026: `scripts/dev finish`, log `build/gradle-logs/run-x35w5B/output.log`, including
  SDK 33 and 28, lint, detekt, coverage (core and Adyen 100%), Markdown and website checks. The first gate exposed a
  same-millisecond stage ordering bug on SDK 28; stages now keep their stored order.
- Real-Adyen gaps: provider onboarding and TEST cards for every provider, entry methods and scanned expiry format,
  StoredValue answers and status recovery, partial redemption fee reporting (a partial approval without
  `TotalFeesAmount` still stops remainder collection conservatively), cash-out, undo and refund behavior, and whether
  terminal totals include stored-value management remain unverified. No real Adyen calls, pushes or releases were made.

Milestone 07 evidence:

- Rechecked Adyen's [Mexican POS installments](https://docs.adyen.com/point-of-sale/installments/mexico-installments/),
  [card installment guide](https://docs.adyen.com/payment-methods/cards/credit-card-installments/),
  [mobile support matrix](https://docs.adyen.com/point-of-sale/ipp-mobile/) and
  [referenced refunds](https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/referenced/)
  on October 26, 2026. POS routing sets `PaymentTransaction.TransactionConditions.DebitPreferredFlag` true for debit,
  false for credit. One-off credit/debit needs no installment data; monthly credit sets `PaymentData.PaymentType` to
  `Instalment` and `PaymentData.Instalment.TotalNbOfPayments`. These fields do not go in `SaleToAcquirerData`.
  The POS page gives a 3–18 range; the card guide names 3, 6, 9, 12 and 18, so the app offers that common subset.
  No undocumented `InstalmentType`, request `PlanID` or Checkout installment plan is sent.
- The POS page identifies Prosa, Mastercard, Nubank, Citibanamex and Bancoppel issuer programs; other issuers require
  a direct agreement. The POS issuer list differs from the generic card guide, so the app does not infer issuer
  acceptance or maintain a guessed BIN list. The merchant confirms program agreements and fees in explicit Payments
  configuration. The issuer decides acceptance; no refusal becomes an ordinary payment automatically. Merchants receive
  the full purchase and pay issuer fees. The existing referenced reversal handles full/amount refunds with original
  context and identity; it does not schedule or cancel monthly token charges. Customer Area confirms completion.
- The feature is offered on local/network and cloud payment terminals. The mobile matrix supports Mexico generally
  but does not document Mexican installments; Payments app/Tap to Pay remains unavailable for this choice. This is a
  conservative app restriction, not a claim that all Adyen mobile integrations lack installments. Other undocumented
  combinations are blocked explicitly: splitting/cash, partial authorization, both tip flows, Giving, audio guidance,
  MOTO/MKE and card saving; links, saved-card charges, scanned wallets and gift cards cannot carry a selected plan.
  Checkout retains a selected option through configuration changes and blocks sending rather than dropping it.
- Readiness uses the existing Management assignment discovery, its assigned store ID, Mexican terminal country,
  original payment context and secret/setup fingerprint, plus explicit merchant configuration and MXN. Language,
  device locale and currency alone establish nothing. The simulator permits only an explicitly configured offline
  demonstration, without claiming verified Mexican account eligibility. The existing setup fence checks freshness
  again at send; new destination support belongs to `DestinationRules`.
- Confirmed pinned SDK 43.1.0 bytecode/models offline: `TransactionConditions.setDebitPreferredFlag(Boolean)`,
  `PaymentData.paymentType/instalment`, `Instalment.totalNbOfPayments` (`BigInteger`) and
  `PaymentResult.paymentType/instalment`/`Instalment.planID`. The SDK getter defaults omitted `PaymentType` to Normal,
  so the app records only explicit installment types; missing count/plan facts remain unreported. No handwritten wire
  models or dependency changes were needed. Returned type/count/issuer PlanID stay separate from the frozen request
  choice on each tender. Schema 21 adds only nullable fields; existing and unfinished rows retain all data.
- Details, History and receipts show requested versus returned facts, using the current receipt language. Original
  Adyen fields, including `productType`, remain verbatim. Seven-language checkout/configuration strings and Using
  Mini mPOS guidance link the official requirements; website tests require each localized section and navigation.
- Tests cover debit/one-off credit and all allowed counts, excluded counts, issuer refusal, partial and omitted plans,
  missing replies with one payment and original status identity, referenced refunds, durable PENDING request facts,
  unknown recovery across a recreated runner and mismatched setup, stale discovery, verified Mexican/non-Mexican and
  unassigned stores, permission loss, conflicting checkout features, historical and unfinished schema upgrades,
  stored-format compatibility and request/answer receipt separation. AMS1 checkout passes in English at 1.5× text,
  Japanese, Simplified Chinese and Traditional Chinese; localization parity and receipt tests cover all seven languages.
  A refused tender retains its returned plan without adding any paid money. Receipt plan notes also survive unresolved
  or refused payments; original Adyen receipt assembly was extracted unchanged to keep the method within its limit.
- Preflight passed (JDK, SDK 37, build tools, Chrome and cached linters). Focused protocol, Checkout, lifecycle,
  discovery, architecture, database, receipt, UI, detekt and website checks completed. Full gate passed on October 26,
  2026: `scripts/dev finish`, log `build/gradle-logs/run-FsJURY/output.log`, including SDK 33 and SDK 28, lint, detekt,
  coverage (core and Adyen 100%), Markdown and website checks. No findings were suppressed.
- Real-Adyen gaps: Mexican TEST merchant/store assignment, issuer-program agreements and eligible cards, supported
  counts per issuer, local/cloud routing, unsupported-issuer declines, unexpected partial/missing answers, receipt
  `productType` disclosure, status recovery and full/partial referenced-refund outcomes remain unverified. Earlier
  milestone gaps remain open. No real Adyen calls, pushes, publications or releases were made.

Milestone 08 evidence:

- Rechecked Adyen's [Brazilian meal vouchers](https://docs.adyen.com/point-of-sale/alternative-payment-methods/brazil-vouchers),
  [Meal vouchers in Europe](https://docs.adyen.com/industries/markets/europe-meal-vouchers), its
  [best practices](https://docs.adyen.com/industries/markets/europe-meal-vouchers/best-practices),
  [Titres-restaurant](https://docs.adyen.com/payment-methods/titres-restaurant),
  [Brazilian installments](https://docs.adyen.com/point-of-sale/installments/brazil-installments),
  [partial authorizations](https://docs.adyen.com/point-of-sale/partial-authorizations) and the
  [mobile support matrix](https://docs.adyen.com/point-of-sale/ipp-mobile) on October 26, 2026. The Titres-restaurant
  in-person link points to a gift-card Terminal API page that no longer exists, and Adyen documents no Terminal API
  payment request for Intersolve vouchers; the mobile matrix lists no meal vouchers.
- Behavior table (`core/voucher/MealVoucherScheme`). The verified Adyen store country picks the market; the merchant
  confirms onboarding in Settings › Payments. Language, locale and currency never establish a real market:

  | Market (store country) | Onboarding | Selection and request | Products | Limits | Short balance | Refunds | Reported |
  | --- | --- | --- | --- | --- | --- | --- | --- |
  | Brazil PAT (BR) | Support enables vouchers (and optionally the terminal screen); CNAE/MCC rules | App: `SaleData.SaleToPOIData` Base64 `{"selectedAccountType":"MEAL_VOUCHER"}`; or terminal screen with an ordinary request | Issuer checks MCC/CNAE; no product flag | None documented | Refused; enter less | Not documented; referenced reversal to the original voucher only | `fundingSource=PREPAID`, `paymentMethodVariant` |
  | Mastercard/Visa vouchers (BE, FI, DE, LU, ES) | Issuer contracts, MID registration per store/voucher type | Terminal recognizes the card; ordinary request plus `AllowPartialAuthorisation` tender option | Eligible products only; never mix in one voucher tender | None documented | Partial approval; rest by split tender | Standard card refund to the original voucher | Ordinary card fields |
  | Titres-restaurant (FR) | CNTR authorization, Conecs MID, issuer contracts, Adyen enables Conecs | Terminal routes Conecs/Mastercard; ordinary request, no partial flag documented at POS | Eligible products only | EUR 25 a day (app caps EUR 25 per sale; issuer enforces daily), no Sundays/public holidays | Refused; enter less | Not supported (Conecs); never refunded through the app or as cash | Ordinary card fields |
  | Intersolve (Monizze/Pluxee BE, Lunch Check CH) | Intersolve enablement | No documented Terminal API payment request | — | — | — | — | Not offered |

- No new SDK model was needed: `SaleToPOIData` is an SDK string field composed by the existing single Base64 owner,
  together with any payment operation; `fundingSource` and `paymentMethodVariant` are flattened `AdditionalResponse`
  keys read by the existing parser. Brazilian `selectedAccountType` is sent only with app-controlled selection; when the
  terminal's own credit/debit/voucher screen is used, the app sends an ordinary payment and recognizes the voucher only
  from the reported documented variants. `selectedAccountType` and Mexican/other card features are mutually exclusive.
- Voucher tenders are split tenders of an immediate sale (amount only, no tip, Giving, audio, MOTO/MKE, saving or
  installments). `Checkout.mealVoucher` explains, in order, unconfigured, no verified market (including Tap to Pay),
  terminal-selected Brazil, closed day, changed setup, unresolved tender and nothing eligible before payment.
  `TenderBalance.acceptsVoucher` checks the eligible gross (custom items never count), the market limit and earlier
  approved/pending/unknown voucher tenders inside the storing transaction; unreported fees count as voucher purchase.
  The tender freezes its market, amount, ServiceID and verified store context before sending; recovery rechecks the
  original Payment category without resending. The simulator offers an explicitly labelled demo per currency.
- Schema 22 adds `products.mealVoucherEligible`, `sale_lines.mealVoucherEligible` (false for existing rows, so nothing
  becomes voucher-payable by migration) and nullable `sale_tenders.mealVoucher`, `fundingSource` and
  `paymentMethodVariant`. Transfers keep version 5 and add product flag bit 1; unknown flag bits are now rejected.
  The setup helper creates no catalogs, so its code and vectors are unchanged; catalog export/import round-trips the
  flag. Shared payment settings gain `mealVouchers` and `mealVoucherAccountSelection` (stored-format snapshot updated).
- Refunds: Titres-restaurant tenders are excluded from refundable legs, a sale they paid alone reports
  `VOUCHER_NOT_REFUNDABLE`, and the review states the kept amount; other voucher legs return only by referenced reversal
  to the original voucher. Cash legs are capped by what cash paid, so voucher value never becomes cash.
- Details, History rows/search, split progress and receipts show the requested market and reported variant/funding
  source separately; Adyen receipt fields stay verbatim. Products offer **Meal-voucher eligible** once vouchers are
  configured. Seven-language strings and Using Mini mPOS sections link the official requirements; website tests check
  labels, navigation and links.
- Tests: Adyen wire shape, conflicting features, simulator answers, missing-answer status recovery, refusals and
  blank facts; core market table, limits, closed days, cart snapshots, transfer flag round-trip and rejection, receipt
  rows; app Brazilian app selection, mixed European basket with partial approval and cash remainder, stored-limit
  refusal, Titres-restaurant cap/Sunday/refund exclusion, explained blocks, verified-country markets, unknown-result
  recovery, decline and stale setup, catalog merge/export, 21→22 upgrade with unfinished tenders, double activation, and
  AMS1 voucher stages in English at 1.5× text, Japanese, Simplified and Traditional Chinese.
- Preflight passed. Full gate passed on October 26, 2026: `scripts/dev finish`, log
  `build/gradle-logs/run-9pGoz8/output.log`, including SDK 33 and SDK 28, lint, detekt, Dokka, coverage (core and Adyen
  100% lines), Markdown and website checks. Earlier gate runs found Android Lint's Spanish typo check on the French
  brand (the Spanish UI now says "vales de comida franceses"), synthetic accessors (helpers made internal) and app branch
  coverage 0.05% under its bound (pure voucher rule tests added). Nothing was suppressed.
- Real-Adyen gaps: every market's merchant/store onboarding, Brazilian `selectedAccountType` and terminal-screen
  behavior on 1.120.11/1.126.5/1.129+ software, returned voucher facts, European voucher recognition and partial
  approvals, Titres-restaurant routing, limits and holiday declines, issuer MID/MCC declines, status recovery and voucher
  refunds remain unverified. Earlier milestone gaps remain open. No real Adyen calls, pushes, publications or releases
  were made.

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
