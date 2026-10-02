# `:terminal-api`

The Terminal API client: `com.adyen:adyen-java-api-library` (nexo models, `TerminalLocalAPI`, `NexoCrypto`) made to work
on Android, with the local, cloud and Payments app transports, the Checkout API calls and the in-process simulator. The
root `AGENTS.md` has the build, the terminal constraints and the conventions; `ArchitectureTest` enforces the starred
rules.

## Packages

- \* Layered downwards: `simulator` → `client` (`TerminalClient`), `checkout` (Checkout API v72: captures and
  adjustments, payment links with `CheckoutPaymentLinks`), `paymentsapp` (Adyen Payments app: App Links,
  `PaymentsAppTransport`, Management API boarding; only the app plugs it in) → `transport` (`TerminalTls`, the local
  and Cloud device API transports) → `parse`. No cycles; every class is in a layer.
- \* Plain Kotlin: no Android, no `:core`, no `:app`. The module's interface uses only its own types (`PrintJob`,
  `ReceiptField`, …). It never logs or prints.
- `checkout` posts JSON with OkHttp + Gson, and the cloud transport uses OkHttp instead of the library's `CloudDeviceApi`:
  the library's Checkout and `tapi` models need Jackson, and keep rules for hundreds of classes.
- The `TerminalSimulator` mirrors Adyen's behaviour (pre-auth blobs, `InProgress` status, cancellations) and shares a
  ledger with its `SimulatedModifications`, so it stands in for both the Terminal API and the Checkout API.

## Sending requests

- \* Every call to Adyen's HTTPS APIs (cloud, Checkout, Management) goes through `transport/AdyenHttp`: no silent
  retries, a per-call timeout, failures typed as sent or not sent. OkHttp requests are built only in `transport`, and
  only `AdyenHttp` and the library's `TerminalHttpClient` make calls.
- \* `TerminalTransport.send` returns a `Delivery` (`Answered`, `NotSent`, `MaybeSent`) and never throws: each transport
  works out once whether a request can have taken effect. Exceptions stay inside the library's HTTP client and the App
  Link exchanges, turned into a `Delivery` with `toDelivery`; `client` and `checkout` never see them.
- Transports that answer status requests themselves (simulator, Payments app) repeat responses from
  `CompletedTransactions`.
- \* Why a transaction was not approved (cancelled, busy, retry advice) is `client/Decline` (`TransactionDetails.decline`,
  and `SaleEntity.decline` in the app); nothing else compares ErrorCondition strings, in either module.

## Adyen Java library on Android

- \* Always set our `TerminalHttpClient` on the `Client`; the library's default Apache client crashes on Android. Inside
  `Client(...).apply { }` a bare `httpClient` calls `Client.getHttpClient()`, which creates it (ArchUnit forbids that
  call, and the library's unencrypted TEST-only `TerminalLocalAPIUnencrypted`).
- `TerminalLocalAPI` always posts to `<endpoint>:8443/nexo/`. `xerces:xercesImpl` supplies `DatatypeFactory`.
- Unknown enum values deserialise to null.
- Application info (`PosApplication`) goes on every payment and refund, formatted identically.
- Upgrading the library: see "Build and verify" in the root `AGENTS.md` (R8 keep rules, release dex).

## Tests

- Encryption is tested against independently generated vectors, TLS against a fake Adyen root certificate.
- `FakePaymentsApp` plays the Payments app (the simulator behind encrypted App Links). No network or DNS in tests: give
  `TerminalHttpClient` a fake `Dns`, and use `mockwebserver` for HTTP.
