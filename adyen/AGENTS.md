# `:adyen`

Android-free Adyen integration. Root `AGENTS.md` has shared constraints; `CONTEXT.md` has vocabulary.
Starred rules are enforced by `ArchitectureTest`. KDoc is the detailed API/protocol reference.

## Architecture boundaries

- \* Layers go downwards: `simulator` → `client`/`checkout`/`paymentsapp` → `transport` → `parse`, with no cycles.
  No Android, `:core` or `:app` dependencies; public interfaces use this module's types. Nothing logs or prints.
- \* Every Adyen HTTPS API call (cloud, Checkout, Management) uses `transport/AdyenHttp`: per-call timeout, no silent
  retries, typed `Fault`s. Only `transport` builds OkHttp requests; only `AdyenHttp` and `TerminalHttpClient` execute
  calls.
- \* `TerminalTransport.send` returns `Delivery` (answered, or failed with a `Fault`), never throws. Each `Fault` states
  once whether the request may have taken effect. Convert transport exceptions there (`FaultException` only inside
  `:adyen`); client/checkout code must not see them. Results carry faults and `ExternalText`, never English sentences
  or exception messages.
- \* Only `client/Decline` interprets ErrorCondition and retry advice. Do not compare its strings elsewhere.
- \* Always install our `TerminalHttpClient` on the Adyen `Client`. The default Apache client crashes on Android;
  even a bare `httpClient` inside `Client.apply` calls its getter and creates it. The getter and unencrypted TEST-only
  `TerminalLocalAPIUnencrypted` are forbidden.

## Integration pitfalls

- \* Use Adyen's library for all API wire models, encryption and certificate checks; app-facing domain types stay local.
  Checkout, Management, boarding and cloud listings use Jackson; nexo uses the library's Gson builder. Preserve
  Jackson annotations/methods/constructors and field names/access in release R8 rules: publicizing private SDK fields
  leaks bookkeeping into JSON. `AdyenModelR8Test` exercises optimized models offline. HTTP still goes through `AdyenHttp`.
  Raw fields are only for unmodeled `refusalReason`, unknown-status diagnostics, extensible `AdditionalResponse`
  data and unstructured terminal rejections.
- `TerminalLocalAPI` posts to `<endpoint>:8443/nexo/`; `xerces:xercesImpl` supplies `DatatypeFactory` on Android.
  Unknown enum values deserialize to null. Keep application info identical on every payment/refund.
- `TerminalSimulator` and `SimulatedModifications` share a ledger; changes must agree across Terminal and Checkout
  operations. Transports answering status themselves use `CompletedTransactions`.
- For library upgrades and release R8 checks, follow `CONTRIBUTING.md` rather than adding Android dependencies here.

## Tests

Use plain JUnit and existing fakes. Encryption vectors are independently generated; TLS uses a fake Adyen root.
`FakePaymentsApp` exercises encrypted App Links with the simulator. Use fake DNS and MockWebServer; no real network
or DNS in tests. JVM code must remain usable at Android API 28; the app's API-level test verifies it.
