# Security policy

Mini mPOS is an independent payment app maintained on a best-effort basis. Only the
[latest release](https://github.com/astiskala/minimpos/releases/latest) receives security fixes; fixes land on `main`
and ship in the next release.

## Report a vulnerability privately

Do not open a public issue for a security problem. Use GitHub's
[private vulnerability report](https://github.com/astiskala/minimpos/security/advisories/new), also available from the
repository's [Security tab](https://github.com/astiskala/minimpos/security).

Include the impact, reproduction steps, version or commit, and whether it reproduces with the simulator or needs a
real terminal. Do not include live credentials or customer data. Expect an acknowledgement within a week; after
confirmation, we'll agree on a fix, disclosure and credit if desired.

Adyen platform, terminal, Payments app, Customer Area or Adyen Java library vulnerabilities belong with
[Adyen's responsible disclosure process](https://www.adyen.com/policies-and-disclaimer/responsible-disclosure).

## Data and trust boundaries

### Payment processing

Adyen's terminal or Payments app reads the card, not Mini mPOS. The app receives payment results and receipt data,
including brand, masked card number, references and, when tokenizing, the stored payment-method ID—not raw card data.
Merchant-scanned wallet payments additionally handle the shopper's payment code transiently in memory. Codes are never
logged, persisted in Room, put in navigation arguments or saved UI state. History stores only the requested wallet and
ordinary transaction facts; missing answers are recovered by transaction status, not by replaying the code.

- **Local Terminal API:** on the same terminal or your network, Adyen's library encrypts and authenticates messages
  with the shared key. TLS certificates must chain to an Adyen TEST/LIVE terminal root and have a terminal name in
  that environment. Network certificates must also match the environment selected in setup.
- **Cloud:** HTTPS requests to Adyen's Cloud device API use the Adyen API key, not shared-key message encryption.
- **Tap to Pay:** App Links to the Payments app are encrypted and authenticated with the shared key. Unverifiable
  answers or answers for a different request are rejected.
- **Other Adyen services:** Checkout handles captures, adjustments and payment links. Management verifies terminal
  assignment and setup, reads receipt details, wallet configuration and capabilities, and applies explicitly requested
  terminal-setting changes. A separate credential boards/revokes the Payments app. These HTTPS calls use API credentials.
- **Wallet artwork:** cached logos load from Adyen's public HTTPS CDN using known brand paths. These requests contain
  no API credentials, payment details or shopper data. Unavailable artwork does not block wallet selection.

A missing answer does not prove a request failed. Operations are persisted before sending; unknown outcomes need
reconciliation, not a fresh charge. Merchant recovery instructions are in
[Troubleshooting](https://minimpos.app/troubleshooting#unknown).

### Local storage

Products, settings and history stay on the device. History includes references, masked payment details and any
collected shopper email/reference. Mini mPOS has no backend, analytics or tracking, and no automatic cross-device
synchronization. QR setup transfers do not back up transaction history.

Shared-key passphrases, API keys, SMTP passwords and PIN verifiers are encrypted with AES-256-GCM using an Android
Keystore key. Admin and Manager PINs use salted PBKDF2 verifiers, not stored PIN text. Incorrect attempts lead to
increasing lockout periods. The two PINs protect separate actions; neither replaces the device's own security.

### Transfers and the setup helper

Secrets in QR transfers are encrypted with AES-256-GCM using a PBKDF2-derived key from a 12-character transfer code.
Every transfer needs the code, even without secrets. The code is displayed separately; the QR images alone do not
reveal the secrets. Anyone with both can decrypt them,
so keep the code private and generate a fresh transfer when needed. Transfers are protected by admin access when an
admin PIN is configured; they are not a remote revocation mechanism.

The [setup helper](https://minimpos.app/setup) generates connection codes locally in a trusted browser. It loads no third-party
resources, sends no form data, and stores no entered credentials. Its Content Security Policy blocks network requests
and form submission. Close the page when finished. Device-to-device copying and browser setup have different import
scopes; see [devices and data](https://minimpos.app/using#more).

Receipts shared through Android are written as a single cached image. The selected app receives read access to that
file, not the app's private database. Email sends receipt/customer information through the configured SMTP service.

## Deployment trade-offs and safeguards

**API keys live on the device.** Adyen recommends server-side storage, but Mini mPOS deliberately has no server.
Encryption at rest does not make a compromised or unlocked device safe. Tablets and phones offer less protection than
payment terminals; prefer a network terminal where practical.

- Use dedicated, least-privilege API credentials with the
  [roles and account access required for your destination](https://minimpos.app/quick-start#credentials).
  Boarding uses a separate Adyen Payments app credential, created automatically with the primary key. For Tap to Pay
  only, the primary key needs credential-management write access: compromise can permit creating or modifying API
  credentials within its account scope. Generated boarding credentials and creation-recovery records stay encrypted on
  this device and are never transferred by QR.
- Set an admin PIN; optionally require a Manager PIN for financial follow-up actions. Keep phone/tablet screen locks
  and Android security updates enabled. Review [staff access](https://minimpos.app/using#business).
- Keep TEST and LIVE credentials separate. Verify the selected or detected environment in Settings › Terminal before
  live payments.
- For a lost or compromised device, revoke its API credentials in the Customer Area and rotate exposed shared keys.
  For Tap to Pay, deregister the installation as well; when you still have the phone, use Remove this phone.
- Use STARTTLS or SSL for SMTP. The None option exposes the email and SMTP password in transit.
- Choose a sensible local history-retention period and avoid sending customer data in public issue reports.
- Payment links are payable by anyone who holds them until expiration. Share them only with the customer, choose an
  appropriate lifetime and cancel unneeded links.
- Keep every device on the latest release. A setup transfer is not a substitute for preserving financial records;
  clearing Android app data permanently removes local history and configuration.
