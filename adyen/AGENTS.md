# `:adyen`

Android-free Adyen integration. Root `AGENTS.md` has shared constraints; `CONTEXT.md` has vocabulary.
Starred rules are enforced by `ArchitectureTest`. KDoc is the detailed API/protocol reference.

## Architecture boundaries

- \* Layers go downwards: `simulator` → `client`/`checkout`/`paymentsapp` → `transport` → `parse`, with no cycles.
  No Android, `:core` or `:app` dependencies; public interfaces use this module's types. Nothing logs or prints.
- \* Every Adyen HTTPS API call (cloud, Checkout, Management) uses `transport/AdyenHttp`: per-call timeout, no silent
  retries, typed sent/not-sent failures. Only `transport` builds OkHttp requests; only `AdyenHttp` and
  `TerminalHttpClient` execute calls.
- \* `TerminalTransport.send` returns `Delivery` (answered/not sent/maybe sent), never throws. Each transport decides
  delivery uncertainty once. Convert transport exceptions there; client/checkout code must not see them.
- \* Only `client/Decline` interprets ErrorCondition and retry advice. Do not compare its strings elsewhere.
- \* Always install our `TerminalHttpClient` on the Adyen `Client`. The default Apache client crashes on Android;
  even a bare `httpClient` inside `Client.apply` calls its getter and creates it. The getter and unencrypted TEST-only
  `TerminalLocalAPIUnencrypted` are forbidden.

## Integration pitfalls

- Use Adyen's library for nexo models, encryption and certificate checks. Checkout/cloud calls deliberately use
  plain JSON with OkHttp: the library's Checkout and `tapi` models pull Jackson and extensive keep rules.
- `TerminalLocalAPI` posts to `<endpoint>:8443/nexo/`; `xerces:xercesImpl` supplies `DatatypeFactory` on Android.
  Unknown enum values deserialize to null. Keep application info identical on every payment/refund.
- `TerminalSimulator` and `SimulatedModifications` share a ledger; changes must agree across Terminal and Checkout
  operations. Transports answering status themselves use `CompletedTransactions`.
- For library upgrades and release R8 checks, follow `CONTRIBUTING.md` rather than adding Android dependencies here.

## Tests

Use plain JUnit and existing fakes. Encryption vectors are independently generated; TLS uses a fake Adyen root.
`FakePaymentsApp` exercises encrypted App Links with the simulator. Use fake DNS and MockWebServer; no real network
or DNS in tests. JVM code must remain usable at Android API 28; the app's API-level test verifies it.
