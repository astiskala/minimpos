# Mini mPOS

A free, open-source point-of-sale app for Adyen Android payment terminals. Sell products, take card and wallet
payments, print or email receipts, and refund by scanning a receipt. Hold deposits and collect tips too, all on one
device, with no extra tablet or app subscription. It also runs on Android tablets and phones, taking payments on a
terminal over your network or the internet, or with Tap to Pay through the Adyen Payments app.

[![CI](https://github.com/astiskala/minimpos/actions/workflows/ci.yml/badge.svg)](https://github.com/astiskala/minimpos/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-0abf53.svg)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-00112c.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-00112c.svg)

**Website:** <https://astiskala.github.io/minimpos/> · **Setup guide:**
<https://astiskala.github.io/minimpos/getting-started.html>

**简体中文:** [网站](https://astiskala.github.io/minimpos/zh-CN/) ·
[快速入门](https://astiskala.github.io/minimpos/zh-CN/getting-started.html) ·
**日本語:** [ウェブサイト](https://astiskala.github.io/minimpos/ja/) ·
[はじめに](https://astiskala.github.io/minimpos/ja/getting-started.html)

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
- **Card and wallet payments** through Adyen, directly on your payment terminal.
- **Customer and transaction references**, and optional **card tokenization** (for later merchant-initiated payments).
  Cards are saved under the customer reference that checkout asks for, or instead under the shopper's email address
  (hashed by default), in which case no customer reference is asked for.
- **Receipts** printed on terminals with a printer as one slip: your header, the items, tax, the card receipt and a
  refund QR code. They can also be **emailed** over your own SMTP server.
- **Referenced refunds**: scan the receipt's QR code, or start from history. Refund in full, by item, or an amount.
- **History** by day with daily totals, filters (sales, awaiting tip, pre-auths, refunds, needs attention) and a
  **search** that finds a transaction by its merchant reference, PSP reference or auth code, the shopper (customer
  reference, shopper reference or email), the card's last 4 digits, its brand or wallet, or an amount (`34` or `34.50`).
  Every word must match, so `visa 34` finds Visa payments of $34.00, and a refund also turns up when you search for its
  sale's shopper or card. A **Payment method** menu narrows the list to one card brand or wallet (Apple Pay, Google
  Pay, Samsung Pay), from the brands and wallets in history. Wallets are shown beside the card brand.
- **Tipping on the receipt** for table service: switch on **Tip on the receipt** at checkout (offered while a printer
  is available) and the receipt prints with blank Tip and Total lines, plus a signature line on the merchant copy. The
  bill is pre-authorized; later, from the result screen or History (filter *Awaiting tip*), enter the tip or the total
  the customer wrote, or **No tip**, and the app captures bill plus tip. Tips above 20% of the bill are authorized
  first, following Adyen's [tipping on the receipt](https://docs.adyen.com/point-of-sale/tipping/tipping-on-receipt)
  flow. History shows each day's tips.
- **Pre-authorizations** for deposits, bookings and hire: mark a product as a pre-authorization product and Home gets a
  **Pre-authorize** tile beside New sale. A pre-authorization holds one product (or custom amount) on the card with
  manual capture, so tapping it goes straight to checkout. From history you **capture** it (less than held releases
  the rest; more is authorized first), **adjust** what it holds, or cancel it. History lists them under their own
  filter and shows what is still held each day.
- **Captures with the optional Checkout API**: enter a merchant account and an API key in Settings › Terminal and the
  app captures tips and pre-authorizations and adjusts their authorization itself (synchronously when the terminal
  returns Adyen's adjustment data). Without an API key, it records the amount and you capture it in the Customer
  Area.
- **Flexible tax**: set your own rates, include tax in prices or add it at checkout, or switch tax off.
- **Any Adyen currency**: all 138 currencies Adyen processes, with Adyen's own decimals, whatever country you are in.
- **Easy import and export**: share products and settings with another terminal using QR codes. Bring passwords
  and keys too, protected by a one-time transfer code. No cloud account or computer needed.
- **Admin PIN** for settings and product management, with automatic re-lock.
- **Made for terminal screens**, down to the AMS1's 4-inch display: layouts tighten on small screens and the main
  action (Pay, Refund, Save, New sale) stays in reach without scrolling.
- **Guided setup**: on a terminal only the shared key is needed. The terminal ID, address and TEST/LIVE environment
  are detected, and Home points to the setup until payments can reach the terminal.
- **Tablets and phones too**: off-terminal, payments go to a terminal on your network (local Terminal API), a terminal
  over the internet (Adyen's Cloud device API, with an API key; the terminal can be chosen from those connected), or
  **Tap to Pay** on the phone itself through the Adyen Payments app, which the app sets up (boards) for you. See
  [Use it on a tablet or phone](#use-it-on-a-tablet-or-phone).
- **Built-in simulator**, so you can try every flow on an emulator or phone without a terminal.
- **English, Simplified Chinese and Japanese**, with receipts laid out for wide characters (see below).

## Language and receipts

The app supports English, Simplified Chinese and Japanese. On Android 12 or earlier it follows the device language;
on Android 13 or later, choose a language in the system's app-language settings. New installations use localized
receipt defaults and sample labels. Existing receipt titles, footers, tax names, email subjects and transferred
catalogs stay as written: review them in Settings › Receipts, Tax and Email (SMTP), and in Products.

Adyen-generated payment receipt data stays verbatim and follows the terminal's own receipt-language setting.
Plain-text receipts account for wide Chinese and Japanese characters, and Japanese receipts show taxable amounts
by rate (税込 / 税抜). This does **not** make them official Chinese 发票 or guarantee Japanese qualified-invoice
compliance; reduced-rate item marking, rounding and registration requirements need separate verification. See the
[language and receipt guidance](https://astiskala.github.io/minimpos/getting-started.html#language-receipts) for details
and official reference links.

## Screenshots

| Selling | Paying | Receipts and refunds | Managing |
| :---: | :---: | :---: | :---: |
| <img src="docs/images/sale.png" width="190" alt="Sale screen"> | <img src="docs/images/checkout.png" width="190" alt="Checkout"> | <img src="docs/images/receipt.png" width="190" alt="Receipt"> | <img src="docs/images/products.png" width="190" alt="Products"> |
| New sale | Checkout | Printed receipt | Products |
| <img src="docs/images/home.png" width="190" alt="Home"> | <img src="docs/images/approved.png" width="190" alt="Approved payment"> | <img src="docs/images/refund.png" width="190" alt="Item refund"> | <img src="docs/images/tax-dialog.png" width="190" alt="Adding a tax rate"> |
| Home | Approved | Refund by item | Tax rates |
| <img src="docs/images/history.png" width="190" alt="History"> | <img src="docs/images/processing.png" width="190" alt="Waiting for the card"> | <img src="docs/images/export.png" width="190" alt="Transfer code and QR codes for another terminal"> | <img src="docs/images/settings.png" width="190" alt="Settings"> |
| History | Waiting for the card | Share with another terminal | Settings |
| <img src="docs/images/pre-auth.png" width="190" alt="Pre-authorizing a catering deposit"> | <img src="docs/images/pre-auth-detail.png" width="190" alt="A pre-authorization in history, with Capture, Adjust amount and Cancel pre-authorization"> | <img src="docs/images/tip-receipt.png" width="190" alt="Printed merchant copy with blank tip, total and signature lines"> | <img src="docs/images/tip.png" width="190" alt="Entering the tip written on the receipt"> |
| Pre-authorize | Pre-authorization in history | Receipt with tip lines | Enter tip |
| <img src="docs/images/history-search.png" width="190" alt="Searching history for a customer reference, with the sale and its refund found"> | | | <img src="docs/images/transfer.png" width="190" alt="Choosing the catalog, settings and passwords to share with another terminal"> |
| Searching history | | | Choose what to share |

Screenshots show an English demo café running on the built-in simulator (hence the "SIMULATOR" banner).
The website's terminal illustrations are illustrative, not product photographs.

## How it works

Mini mPOS runs on the payment terminal and sends Terminal API requests to the terminal's own payment app at
`https://localhost:8443/nexo`, using Adyen's official
[Java API library](https://github.com/Adyen/adyen-java-api-library) (`TerminalLocalAPI`). Requests are encrypted with
your shared key, and the terminal's certificate is checked against Adyen's terminal root certificates bundled with the
app; the root it chains to tells the app whether the terminal is a TEST or LIVE one. Each payment and refund
identifies itself to Adyen as "Mini mPOS" (application info).

Off-terminal, the same requests go to a terminal on the network (with the shared key, at
`https://<address>:8443/nexo`), to Adyen's [Cloud device API](https://docs.adyen.com/point-of-sale/design-your-integration/choose-your-architecture/cloud)
(`/v1/merchants/{merchantAccount}/devices/{POIID}/sync`, authenticated with an API key; the TEST or LIVE endpoint and
data center are found by trying the key), or to the
[Adyen Payments app](https://docs.adyen.com/point-of-sale/mobile-android/build/payments-app) on the same phone, as App
Links encrypted with the shared key. The Payments app is boarded with Adyen's Management API, using an API key with the
Adyen Payments app role.

The app has no backend: products, settings and sales history stay on the terminal. Card details never reach the app;
it only sees what Adyen returns, such as the brand, masked card number, PSP reference and (when tokenizing) the stored
payment method ID. Only if you give it a Checkout API key does it also call Adyen's
[Checkout API](https://docs.adyen.com/api-explorer/Checkout/latest/overview) (`/payments/{pspReference}/captures` and
`/amountUpdates`) to capture tips and pre-authorizations; the key is stored encrypted with the Android Keystore.

The code is split into three modules:

| Module | Contents |
| --- | --- |
| `core` | Plain Kotlin: money and tax math, cart, refunds, receipt layout, catalog and refund QR codes, Adyen's currency table. |
| `terminal-api` | The Terminal API client on top of Adyen's Java library; local, cloud and Payments app transports (OkHttp); retry advice and the simulator. |
| `app` | The Android app: Jetpack Compose (Material 3), Navigation 3, Room, DataStore, CameraX and ZXing, JavaMail. |

## Requirements

- An Adyen account with Terminal API enabled, and Android payment terminals running **Android 9 or later**, for example
  S1F2, S1F4Pro, S1E4Pro, S1E2L, AMS1, S1U2 or SFO1. The older S1E runs Android 7.1 and is not supported. Printing
  needs a terminal with a printer; QR scanning needs a camera.
- Or an Android tablet or phone (Android 9 or later) to use a terminal on your network or in the cloud. For Tap to Pay,
  a Google-certified phone with NFC and Android 12 or later, the Adyen Payments app, and Tap to Pay on Android enabled
  by Adyen Support (see Adyen's [requirements](https://docs.adyen.com/point-of-sale/mobile-android/requirements) and the
  [countries and payment methods](https://docs.adyen.com/point-of-sale/ipp-mobile) it supports).
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
   Then set an admin PIN under Security. For each further terminal, open Settings › Data › **Share with another
   terminal** on this one and **Set up from another terminal** on the new one, and type the transfer code shown.
6. **Optional, for tips on the receipt and pre-authorizations:** create an API credential (Customer Area, Developers ›
   API credentials) with only the **Checkout webservice role**, and enter your merchant account and its API key under
   Settings › Terminal › Checkout API, then tap **Save and test API key**. LIVE terminals also need your live URL prefix
   (Developers › API URLs).

The app meets Adyen's [app requirements](https://docs.adyen.com/point-of-sale/android-terminals/app-requirements): it
only asks for the internet, network state and camera permissions, and the build checks this on every run.

## Use it on a tablet or phone

Install the same APK (sideloaded from the [latest release](https://github.com/astiskala/minimpos/releases/latest)),
then choose under Settings › Terminal › **Payments go to**:

- **A terminal on your network**: the recommended setup for a tablet at the counter. Requests go straight to the
  terminal, encrypted with its shared key, so no API key is needed. Enter the terminal's IP address and POIID as well as
  the shared key. Mini mPOS does not need to be installed on the terminal.
- **A terminal over the internet (cloud)**: for a tablet and terminal on different networks. Create an API credential
  for this device with the **Cloud Device API** role (and the Checkout webservice role for captures), enter the merchant
  account and API key under **Adyen API**, then tap **Find connected terminals** and choose one (or type its POIID).
  Payment requests wait at least 160 seconds, as Adyen requires for cloud payments.
- **Tap to Pay on this phone**: shoppers tap on the phone, in the Adyen Payments app (no card reader). Install
  **Adyen Payments Test** (TEST) or **Adyen Payments** (LIVE) from Google Play, enter the shared key and merchant
  account, then under **Tap to Pay** the **Payments app API key** (a credential with only the Adyen Payments app role,
  which Adyen Support enables) and optionally a store ID, and tap **Set up Tap to Pay**. The environment follows the
  Payments app installed.

> [!WARNING]
> With the cloud and Tap to Pay, an Adyen API key is stored on the device (encrypted with the Android Keystore).
> Adyen advises keeping API keys on a server; Mini mPOS has none. Use a credential for that device only, with only the
> roles it needs, set an admin PIN, and revoke the key in the Customer Area if the device is lost. A terminal on your
> network needs no API key.

## Good to know

- Refunds are processed by Adyen asynchronously, so the app shows them as "Refund requested". The final outcome is in
  your Customer Area; Mini mPOS has no backend to receive webhooks.
- Refunds are always referenced to the original payment. Unreferenced refunds are not supported.
- Pre-authorizations and sales with a tip on the receipt are sent with `authorisationType=PreAuth` and
  `manualCapture=true`, so only they wait for a capture; ask Adyen Support to enable pre-authorization for your payment
  methods first. Wallets and debit cards may not support it.
- Tips up to 20% of the bill are captured on top of what was authorized (overcapture), which Adyen Support has to
  enable for your account; larger tips raise the authorization first. If the issuer refuses, the tip is not saved and a
  smaller one (or none) can be entered. Once entered, a tip cannot be changed.
- Captures and asynchronous adjustments are confirmed by Adyen later, so the app shows "Capture requested"; the final
  outcome is in your Customer Area. A capture whose result is unknown can be sent again from history without
  capturing twice (it reuses its idempotency key). Synchronous adjustments need **Return adjust authorisation data**
  (Customer Area, Devices › Developer › Additional data); without it, adjustments are asynchronous.
- Without a Checkout API key, captures are only recorded in the app: capture the amount shown in the Customer Area
  (Payments › Payment list). Holds expire, so capture tips and pre-authorizations promptly. Canceling one from history
  is a full reversal: Adyen refunds it in full if it was already captured.
- History's wallet (Apple Pay, Google Pay, Samsung Pay) comes from the `paymentMethodVariant` the terminal returns,
  such as `visa_applepay`. Sales taken with earlier versions of the app did not store it, so they are found by their
  card brand only. Search and filters only cover transactions still on the terminal (Settings › Data › Keep
  transactions for).
- Transfer codes that include settings or secrets use format version 4, which older versions of the app cannot read;
  a catalog alone is still sent as version 3. Codes from older versions still import, so update all terminals
  together.
- With Tap to Pay there is no printer (receipts are emailed, so tips on the receipt are not offered), and the Payments
  app takes only payments and referenced refunds. It cannot be asked for a transaction's status, so a payment whose
  answer goes missing (for example when you come back from the Payments app without a result) stays "unknown": check it
  in the Customer Area. An answer that arrives after Mini mPOS was restarted is used by **Check result again**.
- In the cloud, a payment fails straight away when Adyen reports the terminal as not connected; when Adyen got no
  answer from it, the payment is settled with status checks, as on the network.
- Settings from another terminal replace this terminal's, except where payments go, its own address and ID, its Tap to
  Pay installation, the detected TEST/LIVE environment and data center, and the simulator. The shared key passphrase only works if the Customer Area gives
  both terminals the same shared key (for example at store or merchant account level).
- Setting up from another terminal uses the camera, so the receiving terminal needs one.
- Only the latest [release](https://github.com/astiskala/minimpos/releases) is maintained.

## Contributing and security

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and propose changes. Please
report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © 2026 Adam Stiskala.

Adyen is a trademark of Adyen N.V. Mini mPOS is not affiliated with, endorsed by or supported by Adyen.
