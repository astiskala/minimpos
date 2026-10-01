# Security policy

Mini mPOS takes card payments, so security reports are taken seriously, even though it is a small, independent
project maintained on a best-effort basis.

## Supported versions

Only the latest version on the `main` branch gets security fixes.

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
- **Terminal communication is encrypted and authenticated.** Requests go to the terminal's local Terminal API through
  Adyen's Java API library. Messages are encrypted and signed with the shared key from your Customer Area. The
  terminal's TLS certificate must chain to one of Adyen's terminal root certificates (TEST or LIVE), and its name must
  be an Adyen terminal name for that same environment. The environment the app shows comes from this certificate.
- **Secrets are encrypted on the device.** The shared-key passphrase, the optional Checkout API key and the SMTP
  password are encrypted with AES-256-GCM, using a key kept in the Android Keystore. The admin PIN is stored only as a
  salted PBKDF2 hash, and repeated wrong PINs lock entry for increasing periods.
- **Setting up another terminal protects the secrets.** The QR codes that copy a terminal's setup carry the secrets
  only encrypted (AES-256-GCM, with a key derived by PBKDF2 from a one-time 12-character transfer code). The code is
  shown only on the sending terminal and typed on the receiving one; both screens are behind the admin PIN.
- **No backend and no tracking.** Products, settings and sales history stay on the terminal. The app sends nothing
  anywhere except to the payment terminal and, if you set them up, Adyen's Checkout API (to capture tips and
  pre-authorizations) and your SMTP server.

## Recommendations for merchants

- Set an **admin PIN** (Settings › Security), so staff and shoppers can't change settings or products.
- Keep TEST and LIVE shared keys separate. Before taking real payments, check that Settings › About shows "LIVE
  environment" (a TEST terminal also shows TEST in its status bar). Change the shared key if you think it has been
  exposed.
- If you give the app a **Checkout API key**, use an API credential with only the Checkout webservice role, one per
  store or terminal fleet, and revoke it in the Customer Area if a terminal is lost.
- Send receipt email over **STARTTLS or SSL**. The "None" option sends email and your SMTP password unencrypted.
- Choose a sensible **history retention** period (Settings › Data). Sales history includes customer references and
  shopper email addresses.
- Keep the app up to date.
