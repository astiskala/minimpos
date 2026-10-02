# Mini mPOS: domain language

The words the code, tests, docs and reviews use for Mini mPOS's domain, and the one place each concept is decided.
The `AGENTS.md` files (the root one, `app/AGENTS.md`, `terminal-api/AGENTS.md`) hold the rules and how they are
enforced; KDoc holds the details. When code names a concept, use the term here (and its code name); add a term before
naming a new module after it.

Spelling: user-facing text is US English ("pre-authorization"), but identifiers and stored values keep their
original spelling (`preAuthorisation`, `SaleKind.PRE_AUTHORISATION`, `authorisedMinor`). Don't rename either to match
the other.

## Taking payments

- **Sale** (`SaleKind.SALE`, `SaleEntity`): a payment for products and custom items, charged straight away. A stored
  sale is any payment the app sent, whatever its kind. _Avoid_: order, transaction (for the stored record).
- **Pre-authorisation** (`SaleKind.PRE_AUTHORISATION`): a payment that only **holds** one amount on the card until it
  is captured or cancelled; refunded only once captured. _Avoid_: auth, hold (as a noun for the payment).
- **Payment kind** (`SaleKind`): sale or pre-authorisation; each kind has its own products and its own session.
- **Session** (`SaleSession`, `container.session(kind)`): the cart and checkout form being rung up for one kind,
  kept while navigating and cleared once its payment is approved. _Avoid_: basket, draft.
- **Checkout** (`payment/Checkout`): the rules for what payment a session becomes (`PaymentStart`): references, saving
  the card, tip on the receipt, whether it can be paid at all.
- **Tip on the receipt** (`tipOnReceipt`): a sale sent as a pre-authorisation (manual capture) with blank tip lines
  printed; the tip written on paper is entered later and captured with the bill. _Avoid_: gratuity, tipping on the
  terminal.
- **Transaction** (`TransactionLifecycle`, `TransactionBook`): one payment or refund sent to the terminal: written
  PENDING first, at most one at a time, then **settled** (`Settlement`) as succeeded, cancelled, declined, failed (it
  never took effect) or unknown. A **status check** (recheck) asks the terminal for the outcome of one sent earlier; an
  **abort** asks a busy terminal to stop one.
- **Decline** (`client/Decline`): why a transaction was not approved (refused, cancelled, busy) and the retry advice;
  read only from the ErrorCondition. _Avoid_: error, rejection.
- **Payment link** (`payment/PaymentLinks`, `PaymentLinkStart`, `SaleEntity.paymentLink`): a sale paid on Adyen's
  payment page (Pay by Link) instead of on a terminal, through the Checkout API. Stored PENDING first, then **awaiting
  payment** (`SaleStatus.AWAITING_PAYMENT`) once Adyen made the link, then paid (approved), **expired** or cancelled.
  With no server for webhooks, the outcome is learnt by **checking** the link (`PaymentLinks.check`), and a paid link
  has no PSP reference, so it is refunded in the Customer Area. Sales only. _Avoid_: invoice, pay-by-link (in code).
- **References**: the **merchant reference** (Adyen's `reference`: an optional prefix, then `yyMMdd-HHmmss-XXXX`;
  refunds `R-…`, cancellations `C-…`); the **customer reference** typed at checkout, asked for exactly when it is the
  **shopper reference** (Adyen's `shopperReference` for saving a card, made from the customer reference or the email,
  or from nothing, `ShopperReferenceSource.NONE`, when no card is saved).

## After the payment

- **Standing** (`refund/PaymentStanding`, `sale.standing`): where a stored payment stands (charged, awaiting tip, held,
  capture sending/failed/unknown/requested, captured manually, hold cancelled). The one reading of capture status and
  cancellation. _Avoid_: state, phase.
- **Receipt standing** (`ReceiptStanding`): what a sale's receipt says about it (tip lines, held now, captured).
- **Actions** (`StoredPayment.actions`, `PaymentAction`): what the operator can do with a stored payment now: refund,
  cancel, enter tip, capture, adjust.
- **Capture** (`payment/Captures`): taking a held amount, through the Checkout API, or recorded for staff to do in the
  Customer Area (`CaptureMode`). An **adjustment** changes what a pre-authorisation holds before capture; a tip over
  20% of the bill is adjusted first, a smaller one **overcaptured**.
- **Cancellation** (of a hold): a full reversal of a held payment, so nothing is charged. _Avoid_: void, refund.
- **Refund** (`RefundablePayment`, `RefundStart`): a referenced refund of a charged (or captured) payment: everything
  left, an amount, or items of a sale taken on this terminal; found from history or its **refund QR** (`MPR1…`).
  **Refundability** is decided only by `RefundablePayment`.
- **Day totals** and **history search** (`feature/history/HistorySearch`): what the history shows and sums; held
  payments count as "Held".

## Where payments go

- **Destination** (`terminal/Destination`, "Payments go to", `TerminalMode`): this terminal or one on the network
  (`LocalTerminal`), a terminal in the **cloud** (`CloudTerminal`), the **Payments app** on this phone for Tap to Pay
  (`PaymentsAppDestination`), or the **simulator** (`SimulatedTerminal`). What each can do (abort, diagnose, recover a
  missing answer, wait) is its adapter's. _Avoid_: backend, provider, channel.
- **Terminal setup** (`TerminalSetup.resolve`, `TerminalSetupSource`): the one reading of where payments go now: mode,
  POIID, host, environment, Checkout API setup, printer. **Setup problem** (`SetupProblem`): what must still be entered,
  installed or fixed; reported in outcomes, never thrown.
- **Connection** (`Connection`): a destination ready to send (`Open`, with the one `TerminalClient`) or **blocked**
  (`NotSetUp`, `Unreachable`). A **connection check** (`TerminalStatus`, diagnosis) also learns whether there is a
  printer.
- **Delivery** (`transport/Delivery`): what became of one message: answered, **not sent** (it took no effect) or
  **maybe sent** (its outcome must be checked). _Avoid_: result, response (for the failure).
- **Environment** (`TerminalEnvironment`): TEST or LIVE; never a setting: read from the terminal certificate, the
  endpoint that accepts the cloud API key, or the installed Payments app.
- **POIID**: the terminal's ID (`<model>-<serial>`); on a terminal its device name, with the Payments app the boarded
  installation ID. **Shared key**: the key identifier, passphrase and version that encrypt local and Payments app
  messages.
- **Boarding** (`TapToPaySetup`, `TerminalSetup.boarding`): registering the Payments app on this phone with the
  Payments app API key, so Tap to Pay works.
- **Checkout API** (`ApiSetup`, `ApiTarget`, `AdyenApi`): Adyen's online API for captures, adjustments and payment
  links, set up or not (then captures are left to the Customer Area, and there are no payment links).

## Receipts and setup

- **Receipt** (`ReceiptDocument`): one combined slip (header, items, tax, Adyen's card receipt lines, footer), plus the
  refund QR as a second print; the terminal's own receipt printing is suppressed. **Merchant copy**: the second copy,
  printed as `MerchantCopyPolicy` says.
- **Receipt delivery** (`ReceiptDelivery`, `feature/TransactionActions`): offering, printing, emailing and
  **sharing** a transaction's receipt, including the **automatic delivery** of a fresh one. Sharing
  (`share/ShareSheet`) hands the receipt as an image to Android's share sheet, on phones and tablets only.
- **Unpaid receipt** (`SaleReceipt.unpaidLink`): the receipt of a sale awaiting its payment link: marked unpaid,
  totalled as the amount due, with the link as a QR code and an address; emailed as a payment request.
- **Outcome** (`ActionOutcome`, `ActionState`): what a finished action reports, typed, worded only by the screens
  (`feature/OutcomeMessages.kt`). _Avoid_: message, error string.
- **Catalogue**: products, categories and tax rates (`CatalogRepository`); every product has a tax rate.
- **Starter tax** (`StarterTax`): the tax rates (the national standard rate where known, then 0%) and price style
  (tax included or added) a new installation starts with, from the device's country.
- **New-installation settings** (`AppSettings.forNewInstallation`): what a new installation starts with, which may
  differ from the constructor's defaults; those keep meaning what a value left out of a transfer means.
- **Transfer** (`SetupTransfer`, `TransferCodec` `MPC1:`): copying the catalogue, settings and secrets to another
  terminal as QR codes, sealed with a 12-character **transfer code** (`TransferSeal`). **Device fields**
  (`AppSettings.withDeviceFieldsOf`) stay behind.
- **Secrets** (`SecretStore`): the shared-key passphrase, API keys, SMTP password and PIN verifier; encrypted, never
  logged.

## Ambiguities to keep apart

- "Terminal" is the payment device; "Payments go to" names the **destination**, which may be no terminal at all
  (Payments app, simulator). Code says `Destination` for the latter.
- "Held" is a standing (`PaymentStanding.held`); "hold" is what a pre-authorisation does. A sale awaiting its tip is
  held too.
- "Captured" means Adyen received the capture or staff were left to do it; Adyen confirms it only in the Customer Area.
- "Refund" never covers a cancellation: held payments are cancelled, charged ones refunded.
