# Mini mPOS

A small point-of-sale app for Adyen Android payment terminals. Ring up products or custom amounts, take card payments
(or pre-authorize a deposit) through Adyen's Terminal API on the same device, print or email a receipt, and refund a
sale by scanning the QR code on its receipt.

[![CI](https://github.com/astiskala/minimpos/actions/workflows/ci.yml/badge.svg)](https://github.com/astiskala/minimpos/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-0abf53.svg)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-00112c.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-00112c.svg)

**Website:** <https://astiskala.github.io/minimpos/> · **Setup guide:**
<https://astiskala.github.io/minimpos/getting-started.html>

<p align="center">
  <img src="docs/images/home.png" width="200" alt="Home screen">
  <img src="docs/images/sale.png" width="200" alt="Selling products">
  <img src="docs/images/checkout.png" width="200" alt="Checkout with a customer reference">
  <img src="docs/images/receipt.png" width="200" alt="Printed receipt with a refund QR code">
</p>

> [!IMPORTANT]
> Mini mPOS is an independent project. It is not an official Adyen product and is not supported by Adyen. Test it
> thoroughly in your Adyen TEST environment before taking live payments.

## Features

- **Products and categories** in a local database, each with a name, price, optional barcode and tax rate.
- **Sales** from a product grid with search, category filters and barcode scanning, plus keyed-in custom amounts.
- **Card payments** with Adyen's local Terminal API on the terminal itself. Every payment is saved before the terminal
  is called. If the result doesn't come back, the app checks the transaction status, or flags the sale to re-check
  later, so a payment can't silently go missing.
- **Customer and transaction references**, and optional **card tokenization** (for later merchant-initiated payments).
  Cards are saved under the customer reference that checkout asks for, or instead under the shopper's email address
  (hashed by default), in which case no customer reference is asked for.
- **Receipts** printed on terminals with a printer as one slip: your header, the items, tax, the card receipt and a
  refund QR code. They can also be **emailed** over your own SMTP server.
- **Referenced refunds**: scan the receipt's QR code, or start from history. Refund in full, by item, or an amount.
  Like payments, refunds are saved first, and one whose result is unknown can be checked again from history.
- **Pre-authorizations** for deposits, bookings and hire: mark a product as a pre-authorization product and Home gets a
  **Pre-authorize** tile beside New sale. A pre-authorization holds one product (or custom amount) on the card with
  manual capture, so tapping it goes straight to checkout; you capture it in the Customer Area, or cancel it from
  history. History lists them under their own filter and shows what is still held each day.
- **Tax** set up your way: named rates such as GST, VAT, SST, Consumption tax or Sales tax (up to three decimals),
  prices with or without tax, a rate per product (0%, such as Zero rated, for items without tax), or tax switched off.
- **Any Adyen currency**: all 138 currencies Adyen processes, with Adyen's own decimals, whatever country you are in.
- **Catalog transfer** between terminals with a few QR codes: no cloud account or computer needed.
- **Admin PIN** for settings and product management, with automatic re-lock.
- **Made for terminal screens**, down to the AMS1's 4-inch display: layouts tighten on small screens and the main
  action (Pay, Refund, Save, New sale) stays in reach without scrolling.
- **Guided setup**: on a terminal only the shared key is needed. The terminal ID, address and TEST/LIVE environment
  are detected, and Home points to the setup until payments can reach the terminal.
- **Built-in simulator**, so you can try every flow on an emulator or phone without a terminal.

## Screenshots

| Selling | Paying | Receipts and refunds | Managing |
| :---: | :---: | :---: | :---: |
| <img src="docs/images/sale.png" width="190" alt="Sale screen"> | <img src="docs/images/checkout.png" width="190" alt="Checkout"> | <img src="docs/images/receipt.png" width="190" alt="Receipt"> | <img src="docs/images/products.png" width="190" alt="Products"> |
| New sale | Checkout | Printed receipt | Products |
| <img src="docs/images/home.png" width="190" alt="Home"> | <img src="docs/images/approved.png" width="190" alt="Approved payment"> | <img src="docs/images/refund.png" width="190" alt="Item refund"> | <img src="docs/images/tax-dialog.png" width="190" alt="Adding a tax rate"> |
| Home | Approved | Refund by item | Tax rates |
| <img src="docs/images/history.png" width="190" alt="History"> | <img src="docs/images/processing.png" width="190" alt="Waiting for the card"> | <img src="docs/images/export.png" width="190" alt="Catalog QR code"> | <img src="docs/images/settings.png" width="190" alt="Settings"> |
| History | Waiting for the card | Share catalog | Settings |
| <img src="docs/images/pre-auth.png" width="190" alt="Pre-authorizing a catering deposit"> | <img src="docs/images/pre-auth-detail.png" width="190" alt="A pre-authorization in history, with Cancel pre-authorization"> | | |
| Pre-authorize | Pre-authorization in history | | |

Screenshots show a demo café running on the built-in simulator (hence the "SIMULATOR" banner).

## How it works

Mini mPOS runs on the payment terminal and sends Terminal API requests to the terminal's own payment app at
`https://localhost:8443/nexo`, using Adyen's official
[Java API library](https://github.com/Adyen/adyen-java-api-library) (`TerminalLocalAPI`). Requests are encrypted with
your shared key, and the terminal's certificate is checked against Adyen's terminal root certificates bundled with the
app; the root it chains to tells the app whether the terminal is a TEST or LIVE one. Each payment and refund
identifies itself to Adyen as "Mini mPOS" (application info).

The app has no backend: products, settings and sales history stay on the terminal. Card details never reach the app;
it only sees what Adyen returns, such as the brand, masked card number, PSP reference and (when tokenizing) the stored
payment method ID.

The code is split into three modules:

| Module | Contents |
| --- | --- |
| `core` | Plain Kotlin: money and tax math, cart, refunds, receipt layout, catalog and refund QR codes, Adyen's currency table. |
| `terminal-api` | The Terminal API client on top of Adyen's Java library, OkHttp transport, retry advice and the simulator. |
| `app` | The Android app: Jetpack Compose (Material 3), Navigation 3, Room, DataStore, CameraX and ZXing, JavaMail. |

## Requirements

- An Adyen account with Terminal API enabled, and Android payment terminals running **Android 9 or later**, for example
  S1F2, S1F4Pro, S1E4Pro, S1E2L, AMS1, S1U2 or SFO1. The older S1E runs Android 7.1 and is not supported. Printing
  needs a terminal with a printer; QR scanning needs a camera.
- To build: JDK 17 or later (the build downloads JDK 21 for itself) and the Android SDK with platform 37.

## Try it

Run it on an emulator or Android phone; without a terminal it uses the built-in simulator, which approves, declines,
times out or reports a busy terminal as you choose in Settings › Simulator.

```sh
git clone https://github.com/astiskala/minimpos.git
cd minimpos
./gradlew :app:installDebug
```

Or open the project in Android Studio and run the `app` configuration.

## Put it on your terminals

The [Getting started guide](https://astiskala.github.io/minimpos/getting-started.html) walks through all of this,
plus setting up your business, products and pre-authorizations. In short:

1. **Create a shared key** in your Customer Area under **Devices › Device settings › Integrations › Terminal API ›
   Encryption key**, and note its identifier, passphrase and version.
2. **Get a signed APK.** The easiest way is to download `minimpos-<version>.apk` from the
   [latest release](https://github.com/astiskala/minimpos/releases/latest), which is signed by the maintainer.

   Or build and sign your own. Adyen accepts an unsigned upload, but the terminal then refuses it with
   `INSTALL_PARSE_FAILED_NO_CERTIFICATES`. Create a key once and keep it safe:
   ```sh
   keytool -genkeypair -keystore ~/.android/minimpos-release.jks -alias minimpos -keyalg RSA -keysize 4096 \
     -validity 10000 -dname "CN=Mini mPOS"
   ```
   Then add a `keystore.properties` file (never commit it) with `storeFile`, `storePassword`, `keyAlias` and
   `keyPassword`, raise `versionCode` in `version.properties` for every upload, and run
   `./gradlew :app:assembleRelease`. Upload `app/build/outputs/apk/release/app-release.apk`, not
   `app-release-unsigned.apk`.

   Stick to one of the two: once you have uploaded the app, the Customer Area only accepts later versions signed with
   the same key, and rejects others with `INVALID_SIGNING_CERTIFICATE_MISMATCH` until Adyen Support deletes the
   earlier versions.
3. **Upload and deploy** the APK in your Customer Area, as described in Adyen's
   [app deployment guide](https://docs.adyen.com/point-of-sale/android-terminals/deploy-apps). Adyen converts the app
   for the terminals and installs it on the ones you choose.
4. **Set it up on the terminal:** open Mini mPOS and tap **Connect to this terminal** on Home (or go to Settings ›
   Terminal). Enter the key identifier and passphrase (and the version, if it isn't 1), then tap **Save and test** or
   the keyboard's Done key; the result is shown straight away. The terminal's ID, address and TEST/LIVE environment
   are detected automatically.
5. **Set up your business:** Settings › Payments (currency), Tax, Receipts and optionally Email.
   Then set an admin PIN under Security.

The app meets Adyen's [app requirements](https://docs.adyen.com/point-of-sale/android-terminals/app-requirements): it
only asks for the internet, network state and camera permissions, and the build checks this on every run.

## Good to know

- Refunds are processed by Adyen asynchronously, so the app shows them as "Refund requested". The final outcome is in
  your Customer Area; Mini mPOS has no backend to receive webhooks.
- Refunds are always referenced to the original payment. Unreferenced refunds are not supported.
- Pre-authorizations are sent with `authorisationType=PreAuth` and `manualCapture=true`, so only they wait for a
  manual capture; ask Adyen Support to enable pre-authorization for your payment methods first. Capture them in the
  Customer Area (Payments › Payment list), up to the amount held. Adjusting the amount held needs Adyen's Checkout API,
  which the app deliberately does not use (it would need an API key on every terminal), and the app is not told about
  captures. Canceling one from history is a full reversal: Adyen refunds it in full if it was already captured.
- Catalog QR codes now carry whether a product is a pre-authorization product (format version 3). Older catalog
  codes still import, but older versions of the app cannot read the new ones, so update all terminals together.
- Moving a catalog between terminals uses the camera, so the receiving terminal needs one.
- Only the latest [release](https://github.com/astiskala/minimpos/releases) is maintained.

## Contributing and security

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and propose changes. Please
report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © 2026 Adam Stiskala.

Adyen is a trademark of Adyen N.V. Mini mPOS is not affiliated with, endorsed by or supported by Adyen.
