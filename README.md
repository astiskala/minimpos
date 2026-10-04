# Mini mPOS

A free, open-source point-of-sale app that runs **directly on Adyen Android payment terminals**. Ring up products,
take a payment, deliver a receipt and refund it—all on the terminal, with no separate till or app subscription.

The same app works on Android tablets and phones with an Adyen terminal on your network or over the internet,
or with Tap to Pay through the Adyen Payments app.

[![CI](https://github.com/astiskala/minimpos/actions/workflows/ci.yml/badge.svg)](https://github.com/astiskala/minimpos/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-0abf53.svg)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-00112c.svg)

[Website](https://astiskala.github.io/minimpos/) ·
[Download APK](https://github.com/astiskala/minimpos/releases/latest) ·
[简体中文](https://astiskala.github.io/minimpos/zh-CN/) ·
[日本語](https://astiskala.github.io/minimpos/ja/)

<p align="center">
  <img src="docs/images/home.png" width="200" alt="Home screen">
  <img src="docs/images/sale.png" width="200" alt="Selling products">
  <img src="docs/images/receipt.png" width="200" alt="Receipt with a refund QR code">
</p>

Screenshots show an English demo café using the built-in simulator, not live payments.

> [!IMPORTANT]
> Mini mPOS is an independent project, not made, endorsed or supported by Adyen. Test your setup in Adyen's TEST
> environment before taking live payments.

## What you can do

- **Sell:** products, categories, barcode scanning and custom amounts, with your own tax rates and any Adyen currency.
- **Get paid:** card and wallet payments, payment links, pre-authorizations for deposits, and tips written on receipts.
- **Deliver receipts:** print on a terminal with a printer, email through your SMTP server, or share from a tablet or phone.
- **Manage payments:** referenced refunds in full, by item or by amount; searchable history and daily totals.
- **Run your setup:** admin and Manager PINs, optional shopper references and card saving, and QR transfers of products,
  settings and encrypted keys to additional devices.

The app supports English, Simplified Chinese and Japanese. Products, settings and history stay on each device;
there is no backend, tracking or automatic synchronization between devices.

## Try it or set it up

**Just exploring?** Install `minimpos-<version>.apk` from the
[latest release](https://github.com/astiskala/minimpos/releases/latest) on an Android phone (Android 9+) or emulator.
Away from an Adyen terminal, the default is the simulator: no account, keys or real card needed. Choose outcomes and
printer behavior in Settings › Simulator. Payment links are not simulated.

**Ready to connect to Adyen?** Follow [Getting started](https://astiskala.github.io/minimpos/getting-started.html).
It covers supported devices, deployment and all four payment setups. Every real-payment setup needs the Checkout API;
the [setup helper](https://astiskala.github.io/minimpos/setup.html) lets you enter keys on a computer and scan them in.

| Guide | What it covers |
| --- | --- |
| [Getting started](https://astiskala.github.io/minimpos/getting-started.html) | Install, connect, test and go live. |
| [Using Mini mPOS](https://astiskala.github.io/minimpos/using.html) | Configure your business, sell, refund, handle deposits and tips, and manage devices. |
| [Troubleshooting](https://astiskala.github.io/minimpos/troubleshooting.html) | Setup problems, unknown payments, failed operations and recovery. |

## Important boundaries

Mini mPOS has no server to receive Adyen webhooks. Refunds and captures shown as **requested** need final confirmation
in the Customer Area. Paid payment links are refunded there, not in the app. If a payment's result is unknown, check it
before charging again.

API keys are encrypted on the device, but storing them there is a trade-off against Adyen's recommendation to keep
keys on a server. Use narrowly scoped credentials, protect the device, and review [SECURITY.md](SECURITY.md).

## For contributors

See [CONTRIBUTING.md](CONTRIBUTING.md) for building, testing, architecture and releases—including signing your own APK.
Use [CONTEXT.md](CONTEXT.md) for domain terminology. Report vulnerabilities privately through
[the security policy](SECURITY.md), not a public issue.

## License

[MIT](LICENSE) © 2026 Adam Stiskala. Adyen is a trademark of Adyen N.V.
