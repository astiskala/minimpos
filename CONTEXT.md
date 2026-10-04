# Mini mPOS: domain language

Use these terms in code, tests and reviews. The owner column is a navigation aid, not a substitute for KDoc contracts.
Architecture rules live in the module `AGENTS.md` files; development procedures are in [CONTRIBUTING.md](CONTRIBUTING.md).
Add a new concept here before naming a module after it.

User text uses US English, including **pre-authorization** and **catalog**. Identifiers and stored values keep their
existing spelling (`preAuthorisation`, `SaleKind.PRE_AUTHORISATION`, `authorisedMinor`).

## Taking a payment

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Sale | Ordinary payment for products/custom items. A stored sale record also represents other payment kinds. | `SaleKind.SALE`, `SaleEntity` |
| Pre-authorisation | A payment that holds an amount until capture or cancellation; refunded only after capture. | `SaleKind.PRE_AUTHORISATION` |
| Payment kind | Sale or pre-authorisation, each with its own products and session. | `SaleKind` |
| Session | Cart, checkout form and revision for one payment kind, kept across navigation. | `SaleSession`, `container.session(kind)` |
| Checkout | Decides what a session may become: readiness, references, card saving and receipt tipping. | `payment/Checkout`, `PaymentStart` |
| Transaction | A payment/refund sent to the terminal: persisted pending, then settled as succeeded, cancelled, declined, failed or unknown. | `TransactionLifecycle`, `TransactionBook`, `Settlement` |
| Status check / recheck | Requests the original transaction's outcome; not a new payment. | `TransactionLifecycle` |
| Abort | Asks the terminal to stop a current transaction; does not establish its outcome. | `TerminalClient` |
| Decline | Terminal refusal/cancellation/busy reason and retry advice, derived from ErrorCondition. | `client/Decline` |
| Payment link | Sale paid on Adyen's payment page instead of a terminal; awaiting payment after creation, then paid, expired or cancelled. Simulator links are offline demos completed explicitly; their QR codes cannot take payments. | `payment/PaymentLinks`, `PaymentLinkStart` |
| Merchant reference | Generated payment identifier: optional prefix plus `yyMMdd-HHmmss-XXXX`; refunds `R-…`, cancellations `C-…`. | Adyen `reference` |
| Transaction reference | Optional operator-entered reference, separate from the shopper reference. | `CheckoutForm`, `PaymentSettings` |
| Customer reference | Operator-entered identifier requested when it is the shopper reference. | `ShopperReferenceSource.CUSTOMER_REFERENCE` |
| Shopper reference | Identifier sent with every payment: customer reference, email-derived reference, or none. | `ShopperReferenceSource`, Adyen `shopperReference` |
| Saving a card | Tokenization under a shopper reference with consent; an offer at checkout, not a saved-card charging workflow. | `offerCardSaving` |
| Sale event | A happening that changes a stored sale; the sole decision of which statuses/fields it writes. | `data/repo/SaleEvent`, `SaleEntity.after` |

## Following up

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Standing | Where a payment stands: charged, awaiting tip, held, capture requested/failed/unknown, or cancelled. | `refund/PaymentStanding`, `sale.standing` |
| Actions | Operations permitted on a stored payment now. | `StoredPayment.actions`, `PaymentAction` |
| Capture | Collects a held amount through Checkout; accepted requests still need Adyen's final confirmation. | `payment/Captures` |
| Adjustment | Changes or renews an authorization before capture; an unresolved request retains its identity. | `Captures.adjust` |
| Tip on the receipt | Sale held with manual capture; customer writes a tip on paper, then staff enter and capture it. Tips above 20% require adjustment first. | `tipOnReceipt`, `PaymentStanding` |
| Cancellation | Full reversal of a held payment; if already captured externally, Adyen refunds it instead. | `PaymentAction.CANCEL` |
| Refund | Referenced return of a charged payment, full/by amount/by item. Item refunds need the stored original sale. | `RefundablePayment`, `RefundStart`, refund QR `MPR1*` |
| Day totals / history search | Aggregation and matching of retained records currently shown; held funds stay separate from sales. | `feature/history/HistorySearch` |
| Operation identity | Persisted key and request facts for one logical operation; retries reuse them, separate renewals get new ones. | Stored capture, adjustment and link requests |
| Payment context | Non-secret original destination, identity, account and environment used to validate later actions. | `PaymentContext`, `ApiTarget`, `ApiAccess` |

## Connecting to Adyen

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Device | Runs Mini mPOS: terminal, tablet or phone. | `DeviceInfo` |
| Terminal | Takes the card; may be the same device, a network terminal or a cloud terminal. | POIID identifies it |
| Adyen integration | Android-free Terminal, Checkout, Cloud device and Management APIs and simulator. | `:adyen` |
| Destination | Where payments go: local terminal, cloud terminal, Payments app or simulator. | `terminal/Destination`, `TerminalMode` |
| Destination rules | Pure requirements/capabilities: setup, printer, secrets, recovery, timeouts, abort/diagnosis. | `DestinationRules`, adapter companions in `Destinations.kt` |
| Terminal setup | One resolved reading of destination, identity, environment, API setup and printer. | `TerminalSetup.resolve`, `TerminalSetupSource` |
| Setup discovery | Optional read-only terminal and shared-key lookup after saving the Adyen API key; manual setup remains available. | `terminal/SetupDiscovery` |
| Unlocked setup | Resolved setup with required secrets decrypted once. | `UnlockedSetup` |
| Setup problem | Missing/unreadable information or other condition blocking setup, reported typed rather than thrown. | `SetupProblem` |
| Connection | Open with a client, or blocked as not set up/unreachable. | `Connection`, `Destination.connect` |
| Connection check | Checks reachability/setup and learns printer availability when supported. | `TerminalStatus` |
| Delivery | One message was answered, not sent, or maybe sent; maybe sent requires recovery. | `transport/Delivery` |
| Environment | TEST/LIVE selected for network/cloud terminals, otherwise read from this device's certificate or installed Payments app. | `TerminalEnvironment` |
| POIID | Terminal ID (`<model>-<serial>`), device name on terminals, boarded installation ID for Tap to Pay. | `poiId` |
| Shared key | Identifier, passphrase and version encrypting local and Payments app messages. | Terminal settings + `SecretStore` |
| Adyen API key | Credential shared by Checkout/cloud operations and optional Management reads; distinct from the boarding credential. | `ADYEN_API_KEY` |
| Boarding | Registers/revokes the Payments app installation for Tap to Pay. | `TapToPaySetup`, `PAYMENTS_APP_API_KEY` |
| Checkout API | Captures, adjustments and links, required before real payments; access eligibility belongs to the target. | `ApiSetup`, `AdyenApi`, `ApiTarget` |

## Receipts, configuration and access

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Receipt | Combined merchant details, items, tax and Adyen receipt lines; refund QR printed as a second request. | `ReceiptDocument` |
| Receipt standing | What the receipt says: held, tip lines, captured, unpaid link or paid online. | `ReceiptStanding` |
| Merchant copy | Additional receipt copy under the configured policy. | `MerchantCopyPolicy` |
| Receipt tax display | Independent amounts/totals by rate, optional marked rate, marker and explanation. | `ReceiptSettings` |
| Receipt business details | Store receipt name, address and phone read from Management, reviewed before replacing receipt fields. | `terminal/ReceiptBusinessDetails` |
| Receipt delivery | Offers/prints/emails/shares a stored sale or refund; automatic delivery uses the same path. | `ReceiptDelivery`, `TransactionActions`, `StoredTransaction` |
| Unpaid receipt | Link payment request with amount due, QR and address, not proof of payment. | `SaleReceipt.unpaidLink` |
| Outcome | Typed result presented in the current language, worded only at the UI boundary. | `ActionOutcome`, `ActionState`, `OutcomeMessages.kt` |
| Stored reason | Typed app-origin reason; Adyen/terminal messages remain verbatim. | `StoredReason` |
| Catalogue | Products, categories and tax rates. | `CatalogRepository` |
| Pricing change | Confirmed currency/tax-style transition, including durable recovery; numeric prices are preserved and rounded, not FX-converted. | `payment/PricingChanges` |
| Starter tax | Initial country-based rates and price style, not an ongoing regional override. | `StarterTax` |
| New-installation settings | Baseline plus initial country/language choices; never overrides saved settings. | `AppSettings.forNewInstallation` |
| Transfer | Copies catalog, shared settings and sealed secrets by QR; not history or synchronization. | `SetupTransfer`, `TransferCodec` (`MPC1:`) |
| Device fields | Configuration that stays local when shared settings are imported. | `AppSettings.withDeviceFieldsOf` |
| Transfer code | Separate 12-character code for decrypting transferred secrets. | `TransferSeal` |
| Setup helper | Offline browser tool: Automatic transfers the API key and LIVE prefix for device-side discovery; Manual transfers all connection details. Tap to Pay stays manual. | `docs/setup.html`, `ConnectionSetup` |
| Secrets | Shared-key passphrase, API keys, SMTP password and PIN verifiers; encrypted and never logged. | `SecretStore` |
| Admin PIN | Access to configuration and products, separate from financial approval. | `pinManager`, `sessionLock` |
| Manager approval | Optional financial-action access, configured only after admin PIN; rechecked before sending. | `managerPin`, `managerLock`, Manager PIN |

## Avoid ambiguous substitutions

- A stored record is a **sale**, not an order or draft. A **session** is not a basket.
- Use **standing** for the domain reading, **delivery** for whether a request may have taken effect, and **outcome**
  for what a completed action reports; generic state/result/error should not replace these distinct concepts.
- A held payment is **cancelled**, not refunded. **Held** is an amount/standing, not another payment kind.
- A payment link is not an invoice. Receipt delivery **sharing** is not device **transfer** or synchronization.
