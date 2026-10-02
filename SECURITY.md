# Security policy

Mini mPOS takes card payments, so security reports are taken seriously, even though it is a small, independent
project maintained on a best-effort basis.

## Supported versions

Only the [latest release](https://github.com/astiskala/minimpos/releases/latest) gets security fixes. Fixes land on
`main` and ship in the next release, so keep every terminal and device on the latest version.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Report it privately through GitHub instead:

1. Go to the repository's [Security tab](https://github.com/astiskala/minimpos/security).
2. Choose **Report a vulnerability** (or go straight to the
   [new advisory form](https://github.com/astiskala/minimpos/security/advisories/new)).

Please include:

- what the problem is, and what an attacker could do with it;
- the steps or code needed to reproduce it, and the version or commit you tested;
- whether it needs a real terminal, or also shows with the built-in simulator.

You can expect an acknowledgement within a week. Once the issue is confirmed, we'll agree on a fix and on when to
publish the advisory, and credit you if you'd like.

### Adyen's own systems

Problems in Adyen's platform, payment terminals, payment app, Customer Area or the Adyen Java API library are outside
this project. Report them to Adyen through its
[responsible disclosure policy](https://www.adyen.com/policies-and-disclaimer/responsible-disclosure).

## How Mini mPOS protects data

- **Card data never reaches the app.** The terminal's Adyen payment app reads the card. Mini mPOS only receives what
  Adyen returns: card brand, masked card number, PSP reference, receipt lines and, when tokenizing, the stored payment
  method ID.
- **Terminal communication is encrypted and authenticated.** Requests go to the terminal's local Terminal API (on the
  terminal itself, or over your network from a tablet or phone) through Adyen's Java API library. Messages are
  encrypted and signed with the shared key from your Customer Area. The terminal's TLS certificate must chain to one
  of Adyen's terminal root certificates (TEST or LIVE), and its name must be an Adyen terminal name for that same
  environment. The environment the app shows comes from this certificate.
- **In the cloud and with Tap to Pay**, requests to a terminal in the cloud go to Adyen's Cloud device API over TLS,
  authenticated with an API key (they are not also encrypted with the shared key). Requests to the Adyen Payments app
  (Tap to Pay) travel as App Links encrypted and signed with the shared key; answers that cannot be verified with it,
  or that answer another request, are rejected.
- **Secrets are encrypted on the device.** The shared-key passphrase, the API key (Checkout and Cloud device API), the
  Payments app API key and the SMTP password are encrypted with AES-256-GCM, using a key kept in the Android Keystore.
  The admin PIN is stored only as a salted PBKDF2 hash, and repeated wrong PINs lock entry for increasing periods.
- **Setting up another terminal protects the secrets.** The QR codes that copy a terminal's setup carry the secrets
  only encrypted (AES-256-GCM, with a key derived by PBKDF2 from a one-time 12-character transfer code). The code is
  shown only on the sending terminal and typed on the receiving one; both screens are behind the admin PIN.
- **No backend and no tracking.** Products, settings and sales history stay on the device. The app sends nothing
  anywhere except to the payment terminal (or the Adyen Payments app), Adyen's Checkout API (to capture tips and
  pre-authorizations and for payment links) and, if you set them up, Cloud device API (terminals in the cloud),
  Management API (boarding the Payments app) and your SMTP server. A receipt shared from a tablet or phone goes only to
  the app the user picks in Android's share sheet: the app writes the one receipt image to its cache and grants that
  app read access to it alone.
- **API keys on the device are a trade-off.** Adyen advises keeping API keys on a server. Mini mPOS has no backend, so
  the Checkout API key (which every setup needs) lives on the terminal, tablet or phone, and in the cloud and for Tap to
  Pay so do the keys that reach the terminal or board the phone. A tablet or phone is less protected than a payment
  terminal.

## Recommendations for merchants

- Set an **admin PIN** (Settings › Security), so staff and shoppers can't change settings or products.
- Keep TEST and LIVE shared keys separate. Before taking real payments, check that Settings › About shows LIVE as the
  environment (a TEST terminal also shows TEST in its status bar). Change the shared key if you think it has been
  exposed.
- For the **Checkout API key**, use an API credential with only the Checkout webservice role, one per store or
  terminal fleet, and revoke it in the Customer Area if a terminal is lost.
- On a **tablet or phone**, prefer a terminal on your network. For the cloud or Tap to Pay, create an API credential
  for each device with only the roles it needs (Cloud Device API and Checkout webservice; or only the Adyen Payments
  app role for boarding), keep the device's screen lock and Android security updates on, set an admin
  PIN, and revoke the credential (and, for Tap to Pay, remove the phone in Settings › Terminal) if the device is lost.
- Send receipt email over **STARTTLS or SSL**. The "None" option sends email and your SMTP password unencrypted.
- Choose a sensible **history retention** period (Settings › Data). Sales history includes customer references and
  shopper email addresses.
- A **payment link** can be paid by whoever has it until it expires: send it only to the customer, keep its lifetime
  (Settings › Payments) short, and cancel a link you no longer need.
- Keep the app up to date (see [Supported versions](#supported-versions)).
