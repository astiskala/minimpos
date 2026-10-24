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
| Financial operation | A terminal transaction, saved-card charge or Checkout refund being sent; one place routes its status check and refund, and says whether any is running. | `payment/FinancialOperations`, `CheckoutOperations`, `CheckoutBook` |
| Status check / recheck | Requests the original operation's outcome the way it was sent; not a new payment. Offered from the stored reading. | `FinancialOperations.recheck`, `PaymentAction.CHECK_AGAIN`, `RefundEntity.canCheckAgain` |
| Abort | Asks the terminal to stop a current transaction; does not establish its outcome. | `TerminalClient` |
| Decline | Terminal refusal/cancellation/busy reason and retry advice, derived from ErrorCondition. | `client/Decline` |
| Payment link | Sale paid on Adyen's payment page instead of a terminal; awaiting payment after creation, then paid, expired or cancelled. Simulator links are offline demos completed explicitly; their QR codes cannot take payments. | `payment/PaymentLinks`, `PaymentLinkStart` |
| Scanned wallet | Immediate-charge sale using a shopper-presented numeric payment code; selected wallet is intent, the terminal answer is authoritative. Raw codes are transient, status recovery never replays them. PayMe is explicitly best-effort. | `core/payment/ScanWallet`, `terminal/WalletDiscovery`, `payment/WalletPayments` |
| Merchant reference | Generated payment identifier: optional prefix plus `yyMMdd-HHmmss-XXXX`; refunds `R-…`, cancellations `C-…`. | Adyen `reference` |
| Transaction reference | Optional operator-entered reference, separate from the shopper reference. | `CheckoutForm`, `PaymentSettings` |
| Customer reference | Operator-entered identifier requested when it is the shopper reference. | `ShopperReferenceSource.CUSTOMER_REFERENCE` |
| Shopper reference | Identifier sent with every payment: customer reference, email-derived reference, or none. | `ShopperReferenceSource`, Adyen `shopperReference` |
| Saving a card | Tokenization under a shopper reference with consent; an offer at checkout. Charging the token later is a saved-card charge. | `offerCardSaving` |
| Saved-card charge | **Charge** in user text. Immediate-charge sale paid with a card token saved for a shopper reference, through Checkout with the shopper not present (`ContAuth`). Rung up in its own session (Home’s Tokens) or in New sale's, from its checkout once the entered shopper reference has tokens; persisted pending with the card and recurring model, sent with one idempotency key per sale; an unknown charge is resent within seven days. Refunded through Checkout, never a terminal; its refund QR code (a `.`-prefixed transaction ID) works only on the device that charged it. | `payment/SavedCardCharges`, `payment/CheckoutRefunds`, `SaleEntity.savedCard`, `container.savedCardSession` |
| Saved-card lookup | Memory-only reading of cards for one shopper reference, recurring model and current payment context/credential. Automatic checkout lookup and explicit Tokens lookup share freshness and chargeability; stale answers cannot start a charge. | `payment/SavedCardLookup`, `SavedCardCharges.lookup` |
| Token deletion | Confirmed merchant/shopper token removal at Adyen; prevents future token payments across integrations, preserving historical payments and referenced refunds. Requires a configured Manager PIN under the capabilities plan’s provisional default. Memory-only evidence, shared invalidation and fresh-list reconciliation after uncertainty; never silently resend DELETE. | `payment/SavedCardCharges`, `TokenDeletionOffer`, `TokenDeletionState` |
| Audio guidance | Per-shopper Terminal API `EnableAccessibilityMode` payment on an eligible, Support-enabled Android terminal; incompatible with tipping, Giving and signature CVM. Model support is advisory; the merchant confirms software, enablement and card/language suitability per payment. | `CheckoutForm.audioGuidance`, `SaleEntity.audioGuidance`, `TerminalClient` |
| Terminal tipping | Immediate-charge sale that prompts for a tip on the terminal (`AskGratuity`), optionally overriding suggestions (`TipSuggestions`) and the amount to tip on (`AmountToTipOn`) in `SaleData.SaleToPOIData`. | `PaymentSettings.terminalTipping`, `TerminalTipOptions`, `SaleEntity.tipMinor` |
| Shopper display | Chained `AdminRequest` session around a payment on an external terminal (or simulator) showing a virtual receipt (`DisplayRequest`), basket confirmation, email entry (once emailed receipts are ready; then emailed like a checkout email), QR receipt and 0–10 rating (`InputRequest`). | `ShopperSessionFlow`, `SaleEntity.shopperRating`, `AppSettings.asksEmailOnTerminal` |
| Surcharge | Adyen configuration-based fee reported as `TotalFeesAmount`, included alongside tip and purchase in `AuthorizedAmount`. Unknown historical or omitted components stay unknown; each tender retains its charged amount, fees and original context. | `TransactionDetails.surchargeMinor`, `SaleTenderEntity`, `SaleEntity.surchargeMinor` |
| Partial authorization / split tender | Immediate-charge sale that accepts partial approvals (`AllowPartialAuthorisation`, `Result=Partial`), then collects the remaining balance on further tenders (`SaleTenderEntity`), tracking paid vs remaining balance until paid in full or reversed. | `SaleTenderEntity`, `PaymentStanding.PARTIALLY_PAID`, `Checkout.remainingPayment`, `SaleEvent.Settled` |
| Split payment | Immediate-charge sale divided across tenders by operator amount, equal share or sold item units; the cart is frozen into the stored sale when the first tender starts, sale-level prices and tax never change, and every allocation is checked again where its tender is stored. Unresolved card tenders block further tenders. | `payment/Checkout.splitStart`, `data/repo/TenderBalance`, `core/split/TenderSplit` |
| Tender allocation | Frozen share (`index`/`count`/`baseMinor`) or item-unit allocation of one tender; remainders go to the first shares and the last share takes what is unpaid. Approved, pending and unknown tenders keep their units. | `TenderAllocation`, `SaleTenderEntity.allocationJson` |
| Cash tender | Cash received, change and the confirmed amount recorded locally without a Terminal API call, with its history environment; in local reports, never in terminal totals. | `payment/CashTenders`, `TenderKind.CASH` |
| Refund legs | A return proposed across a sale's original tenders and adjusted by staff within each balance; each leg is persisted before it is sent, cards first in their original contexts, cash handed back last (configured Manager PIN, provisional default). A leg that is not accepted stops the rest. | `RefundablePayment.proposal`, `payment/RefundLegs`, `RefundEntity.cash` |
| Adyen Giving | Charity donation prompt on the terminal after purchase (`AskGiving` tender option); when `adyen_giving_service_id` is returned, queries `TransactionStatus` (`SaleID=AdyenGiving`) for the donation amount without affecting merchant accounting. Links, scanned wallets and tokens invite no donation; the tender ceremony confirms that first. | `PaymentSettings.askGiving`, `TerminalClient.pay`, `SaleEntity.donationMinor` |
| Tender | A way to pay a checkout: on the terminal, by payment link, scanned wallet, token or, within a split, cash. | `payment/Tender`, `SaleTenderEntity` |
| Tender ceremony | What paying with one tender needs first, in order: confirming what it drops (tip, donation invitation), the special-entry warning, then Manager approval. Decided next to the starts that drop those options. | `Checkout.ceremony`, `TenderCeremony` |
| Payment progress | The payment running or just finished, whichever way it is sent (terminal, scanned wallet, token), with the amount its request asks for: the balance when collecting a remainder. Waiting and result screens follow it; leaving the result acknowledges it. | `FinancialOperations.paymentProgress`, `TransactionState.Processing.amount` |
| Special card entry (MOTO / MKE) | Optional card-entry override (`MOTO` tender option for mail/telephone orders, or `ForceEntryMode=Keyed` for Manual Key Entry); requires explicit fraud/liability confirmation and Manager PIN approval rechecked before sending. | `CardEntryOverride`, `PaymentSettings.allowMoto`, `PaymentSettings.allowMke` |
| Offline payment | Local-terminal transaction approved offline (`OnlineFlag=false`, `offlineAuthCode` for Offline EMV vs Store-and-forward, `unconfirmedBatchCount`); surfaces outstanding unforwarded offline batches in History and reconciliation. | `OfflineProcessingMode`, `SaleEntity.offlineMode`, `SaleEntity.unconfirmedBatchCount` |
| Sale event | A happening that changes a stored sale; the sole decision of which statuses/fields it writes. | `data/repo/SaleEvent`, `SaleEntity.after` |

## Following up

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Stored payment | Read-only interpretation of a retained sale: standing, allowed actions, refund eligibility and history accounting. Separate from sending financial operations. | `history/StoredPayment`, `history/PaymentStanding`, `history/RefundablePayment`, `history/HistoryAccounting` |
| Standing | Where a payment stands: charged, awaiting tip, held, capture requested/failed/unknown, or cancelled. | `history/PaymentStanding`, `sale.standing` |
| Actions | Operations permitted on a stored payment now; refund/cancellation initiation and capture retry keep reference facts out of screens. | `StoredPayment.actions`, `PaymentAction`, `payment/StoredPaymentActions` |
| Capture | Collects a held amount through Checkout; accepted requests still need Adyen's final confirmation. | `payment/Captures` |
| Adjustment | Changes or renews an authorization before capture; an unresolved request retains its identity. | `Captures.adjust` |
| Tip on the receipt | Sale held with manual capture; customer writes a tip on paper, then staff enter and capture it. Tips above 20% require adjustment first. | `tipOnReceipt`, `PaymentStanding` |
| Cancellation | Full reversal of a held payment; if already captured externally, Adyen refunds it instead. | `PaymentAction.CANCEL` |
| Refund | Referenced return of a charged payment, full/by amount/by item. Item refunds need the stored original sale. | `RefundablePayment`, `RefundStart`, refund QR `MPR1*` |
| Day totals / history search | Processing-day aggregation of retained activity currently shown; capture and tip count on acceptance day, new holds stay separate. Issue filtering uses the same accounting activity reading. | `HistoryAccounting.days`, `feature/history/HistorySearch` |
| Issue | What about a stored sale still needs attention: its outcome, an adjustment or its capture; one reading for filters, totals and reconciliation. | `SaleEntity.issues`, `PaymentIssue` |
| Daily report | Unfiltered whole-day local summary; currencies/environments separate, undated links/captures excluded from dated net. Seeded samples count as demos and disappear with sample purge. | `HistoryAccounting`, `ReceiptDelivery` |
| Terminal reconciliation | Retrieves terminal totals (`GetTotalsRequest`) or closes the current reconciliation period (`ReconciliationRequest` with `SaleReconciliation`), rechecking unresolved terminal operations first and comparing terminal debit/credit totals against local accounting. Opened from Home's Reconciliation screen: check totals, close the period (confirmed, Manager-approved), print today's report. | `TerminalClient.getTotals`, `TerminalClient.reconcile`, `HistoryAccounting.compareReconciliation` |
| History environment switch | Explicitly confirmed LIVE/TEST/simulator transition deletes all local operations, including unfinished ones; durable non-secret journal completes destination settings. | `terminal/HistorySwitches` |
| Operation identity | Persisted key and request facts for one logical operation; retries reuse them, separate renewals get new ones. | Stored capture, adjustment and link requests |
| Payment context | Non-secret original destination, identity, account and environment used to validate later actions. | `core/payment/PaymentContext`, `ApiTarget`, `ApiAccess` |

## Connecting to Adyen

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Device | Runs Mini mPOS: terminal, tablet or phone. | `DeviceInfo` |
| Terminal | Takes the card; may be the same device, a network terminal or a cloud terminal. | POIID identifies it |
| Adyen integration | Android-free Terminal, Checkout, Cloud device and Management APIs and simulator. | `:adyen` |
| Destination | Where payments go: local terminal, cloud terminal, Payments app or simulator. | `terminal/Destination`, `TerminalMode` |
| Destination rules | Pure requirements/capabilities: setup, printer, secrets, recovery, timeouts, abort/diagnosis. | `DestinationRules`, adapter companions in `Destinations.kt` |
| Destination capabilities | What the resolved destination can do and asks of its setup, carried as one value to setup screens and status. | `DestinationCapabilities`, `TerminalSetup.capabilities` |
| Terminal setup | One resolved reading of destination, identity, environment, API setup and printer. | `TerminalSetup.resolve`, `TerminalSetupSource` |
| Setup discovery | Read-only terminal assignment, optional shared-key lookup and merchant-scoped Tap to Pay store selection; immutable operation outcomes distinguish unavailable discovery, failures and manual completion. Terminal access and every required API role (`GET /me`; the cloud device role only for cloud terminals) are verified for every real setup. | `terminal/SetupDiscovery`, `SetupAccess`, `AdyenRole` |
| Terminal assignment | Pure reading and verified Management query of listed POIIDs, merchant accounts and stores; discovery may fill a blank account, access requires an exact account, and shared-key creation also requires an unchanged store and unique identity. | `terminal/TerminalAssignments`, `TerminalListingApi` |
| Adyen capabilities | Progressive reading of destination support, Management `terminalSettings` (`gratuities`, `dcc`, `surcharge`, `moto`, `offlineProcessing`, `storeAndForward`) and POS payment methods, plus scoped non-secret `terminalSettings` updates after unchanged-assignment verification. | `terminal/CapabilityDiscovery`, `SettingsSections.CAPABILITIES` |
| Verified setup | Local fingerprint of checked connection fields and encrypted payment secrets; changed facts invalidate it, not a process restart. What it covers is decided once; only the setup source compares it. | `data/settings/VerifiedSetup`, `TerminalSetupSource`, `AppSettings.verifiedSetup` |
| Setup plan | The steps Settings › Terminal shows for a destination: which, in what order and numbering, when each is revealed and ticked. | `terminal/TerminalSetupPlan`, `DestinationRules.setupSteps` |
| Setup gate | Holds back every action reaching Adyen or a terminal until the setup is verified: the action stays pressable and explains generically, linking to Settings › Terminal. Setup tools and status checks of unfinished operations never wait. | `TerminalState.needsSetup`, `SetupGate`, `GatedButton(requiresSetup)` |
| Verified setup import | Authenticated QR candidate checked before activation; encrypted commit journal resumes interrupted saves, with an atomic catalog import receipt. | `terminal/SetupImport`, `data/transfer/SetupTransfer` |
| Unlocked setup | Resolved setup with required secrets decrypted once. | `UnlockedSetup` |
| Setup fence | What work begun on one setup checks before its result applies: connection fields, plus the API key, or the exact secret snapshot. | `TerminalSetupSource.SetupFence`, `SetupFreshness` |
| Setup problem | Missing/unreadable information or other local condition to enter, install, approve or reconcile first, reported typed rather than thrown. What Adyen refuses or fails to answer is a fault, not a setup problem. | `SetupProblem` |
| Connection | Open with a client, or blocked as not set up/unreachable. | `Connection`, `Destination.connect` |
| Connection check | Checks reachability/setup and learns printer availability when supported. | `TerminalStatus` |
| Delivery | One message was answered, or failed with a fault; a fault that may have taken effect requires recovery. | `transport/Delivery` |
| Fault | Why an exchange with Adyen, a terminal or the Payments app produced no usable result; each case states once whether the request may have taken effect. Typed, worded only at the UI. | `transport/Fault` |
| External text | Words written by Adyen, a terminal, the Payments app or a mail server, shown verbatim and never used to decide. | `ExternalText` |
| Environment | TEST/LIVE selected for network/cloud terminals and Tap to Pay; otherwise read from this device's certificate. Tap to Pay opens only the selected app, even with both installed; changing environment drops its stored installation ID. Learned facts retain their original terminal setup and cannot update changed connection settings. Kept across a destination change while the API key and merchant account it belongs to stay. | `TerminalEnvironment`, `TerminalSetupSource.remember`, `TerminalSettings.selectDestination` |
| POIID | Terminal ID (`<model>-<serial>`), device name on terminals, boarded installation ID for Tap to Pay. | `poiId` |
| Shared key | Identifier, passphrase and version encrypting local and Payments app messages. | Terminal settings + `SecretStore` |
| Shared-key creation | Confirmed terminal-specific creation after read-only checks; encrypted recovery retains one generated key per context until setup verifies. Never replaces an existing effective key. Saved-setup completion includes a fenced save and exact-key connection verification; setup import keeps its separate verify-before-activation journal. | `terminal/SharedKeySetup`, `SetupDiscovery.setupKey` |
| Adyen API key | Credential shared by Checkout/cloud operations and optional Management reads; for Tap to Pay it also creates the boarding credential, which stays distinct from it. | `ADYEN_API_KEY` |
| Boarding | Registers/revokes the Payments app installation for Tap to Pay with a boarding credential: a dedicated merchant-scoped credential and client key that the Adyen API key creates automatically; generated secrets stay device-local. | `TapToPaySetup`, `BoardingCredential` |
| Tap to Pay troubleshooting | Advisory boarding-credential/client-key checks and redacted, manually shared diagnostics; never proof of payment readiness. | `terminal/TapToPayDiagnostics`, `TapToPayCheck`, `BoardingIdentity` |
| Checkout API | Captures, adjustments, links and saved cards, required before real payments; made once per credential, and access eligibility belongs to the target. | `ApiSetup`, `AdyenApi`, `ApiTarget`, `CheckoutApi`, `AdyenCheckout` |

## Receipts, configuration and access

| Term | Meaning | Owner / code name |
| --- | --- | --- |
| Receipt | Combined merchant details, items, tax and Adyen receipt lines; refund QR printed as a second request. | `ReceiptDocument`, `ReceiptFactory`, `ReceiptTexts` |
| Receipt kind | What a stored transaction's receipt is (sale, pre-authorization, unpaid link, demo link, refund or cancellation); its document and its email subject and introduction both follow it. A paper receipt, unlike an emailed or shared one, gets blank tip lines. | `receipt/ReceiptKind`, `ReceiptSurface` |
| Receipt standing | What the receipt says: held, tip lines, captured, unpaid link, paid online or offline demo. Demo meaning follows the stored payment context even after settlement or a destination change. | `ReceiptStanding`, `sale.simulatedLink` |
| Merchant copy | Additional receipt copy under the configured policy. | `MerchantCopyPolicy` |
| Receipt tax display | Independent amounts/totals by rate, optional marked rate, marker and explanation. | `ReceiptSettings` |
| Receipt business details | Assigned-store receipt name, address and phone, or merchant-account legal name, read from Management; automatic imports fill selected blank fields without replacing merchant text. | `terminal/ReceiptBusinessDetails` |
| Receipt delivery | Offers/prints/emails/shares a stored sale or refund. First screen claims automatic delivery; receipt module owns its dispatch through completion after navigation. One attempt per arm in this process, not a durable queue or uncertain-send retry; manual printing stays screen-scoped. | `ReceiptDelivery`, `TransactionActions`, `StoredTransaction`, `AutoDelivery` |
| Unpaid receipt | Link payment request with amount due, QR and address, not proof of payment. | `SaleReceipt.unpaidLink` |
| Outcome | Typed result presented in the current language, worded only at the UI boundary. | `ActionOutcome`, `ActionState`, `OutcomeMessages.kt` |
| Failure | Why an action did not happen or was not confirmed: not set up, a fault, a device fault or an email fault. One sentence with its fix, details in brackets. | `data/db/Failure` |
| Device fault | Failure on the device running Mini mPOS (secure storage, database, files, unexpected); exception text is never kept. | `DeviceFault` |
| Email fault | Why a receipt email was not sent; the mail server's reply stays external text. | `EmailFault` |
| Stored reason | Typed reason stored with a transaction or capture: not done, unconfirmed (with what prevented confirmation) or interrupted. Answer text remains verbatim. | `StoredReason` |
| Catalogue | Products, categories and tax rates. | `CatalogRepository` |
| Sample data | Optional demo products, categories and read-only simulated history shared with screenshot fixtures, tracked separately for scoped removal. Onboarding fills only an empty receipt business profile; the Settings sample action explicitly replaces business fields. Existing merchant catalog/history and credentials stay intact. | `data/repo/SampleData`, `sample` on stored rows |
| Onboarding | First-run intent choice: offline demo with samples, connect to Adyen or scan setup codes; confirmed with Continue. Terminals initially select connecting, other devices the demo. QR guidance precedes scanning. Back from setup revisits the selected choice; Home ends the first-run stack. Confirmed intent stays local. Simulator settings offer an explicit, non-mutating setup handoff and sample/receipt review. | `feature/onboarding/OnboardingScreen`, `feature/settings/DemoExit`, `AppSettings.onboardingCompleted` |
| Self-update | Startup check for a newer version on a device that is not an Adyen terminal; the offer opens the release's APK in the browser, which downloads it. Terminals update through the Customer Area. | `update/AppUpdate`, `update/GitHubReleases` |
| Pricing change | Confirmed currency/tax-style transition, including durable recovery; numeric prices are preserved and rounded, not FX-converted. | `payment/PricingChanges` |
| Starter tax | Initial country-based rates and price style, not an ongoing regional override. | `StarterTax` |
| New-installation settings | Baseline plus every country and language default (settings, receipt texts, starter tax rates and their names), decided once and worded through a text adapter; never overrides saved or imported settings. | `data/settings/NewInstallation`, `InstallationText` |
| Transfer | Copies catalog, shared settings and sealed secrets by QR; not history or synchronization. | `SetupTransfer`, `TransferCodec` (`MPC1:`) |
| Device fields | Configuration that stays local when shared settings are imported. | `AppSettings.withDeviceFieldsOf` |
| App language | Device-local display-language override, empty to follow Android; changes current UI and receipt labels without changing pricing or saved merchant text. Languages appear under their own names (CLDR) in BCP 47 tag order. | `AppSettings.languageTag`, `MiniMposApp` |
| Transfer code | Separate 12-character code for decrypting transferred secrets. | `TransferSeal` |
| Setup helper | Offline browser tool: Automatic transfers credentials for physical-terminal lookup; Manual transfers complete connection details. Every helper QR declares TEST/LIVE for review; an Adyen terminal's certificate still determines its actual connection environment. Every transfer requires its code and verification before activation. Tap to Pay registers the phone during the confirmed import and requires a supplied shared key. Verified setup returns Home; saved details stay editable. Receipt text/business lookup and SMTP settings are opt-in; receipt lookup can be selected per field. | `docs/setup.html`, `ConnectionSetup` |
| Storage health | Reads settings and secrets before exposing protected screens or starting recovery and terminal checks; unreadable files are preserved, access stays blocked until restart, and writes never report success after storage failure. | `data/settings/StorageHealth` |
| Secrets | Shared-key passphrase, API keys, SMTP password, PIN verifiers and typed encrypted recovery journals; encrypted and never logged. | `SecretStore`, `EncryptedJournal` |
| Repository tooling | Kotlin/JVM release integrity, signing-property parsing and docs image processing/review, plus Bash runners for private build logs, disposable docs emulators and read-only release checks; not device application code. No Python. | `:tooling`, `scripts/`, `GuideLayoutTest` |
| Admin PIN | Access to configuration and products, separate from financial approval. | `pinManager`, `sessionLock` |
| Manager approval | Optional financial-action access, configured only after admin PIN. An operation keeps the approval it was started with until it is sent: leaving the approving screen does not revoke it, the auto-lock time does. Checked again before sending; follow-ups without a start of their own check the session as it is now. | `data/security/ManagerApproval`, `managerPin`, `managerLock`, Manager PIN |
| Blocked action | An action whose precondition is known before it is pressed: disabled, with the typed reason and its fix shown under it. | `GatedButton`, `BlockedReason`, `TerminalState.printBlocked`, `TerminalState.totalsBlocked` |

## Avoid ambiguous substitutions

- A stored record is a **sale**, not an order or draft. A **session** is not a basket.
- Use **standing** for the domain reading, **delivery** for whether a request may have taken effect, and **outcome**
  for what a completed action reports; generic state/result/error should not replace these distinct concepts.
- A **decline** is not a **fault**: the terminal answered. A **failure** is worded by the app; **external text** is
  never reworded, and exception text is never shown.
- A held payment is **cancelled**, not refunded. **Held** is an amount/standing, not another payment kind.
- A payment link is not an invoice. Receipt delivery **sharing** is not device **transfer** or synchronization.
