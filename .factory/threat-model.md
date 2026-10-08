# Mini mPOS threat model

Version: 1.0.0. Updated: 2026-10-08. Method: STRIDE. Local pre-deployment audit context, not a certification.

## 1. System Overview

Mini mPOS is a Kotlin Android point-of-sale application with no backend. Android Compose provides the UI.
Room stores catalog and financial history; DataStore stores settings; Android Keystore protects encrypted secrets.
The Android-free core owns money, taxes, receipts and transfer formats. The Adyen module integrates terminal,
Checkout, Management and Payments app protocols. Website tests validate local multilingual guides and the offline
setup helper. JVM tooling prepares versions, verifies release artifacts and parses signing properties.

Payments persist their identity and request facts before transmission. The terminal or Payments app takes the card.
Results flow through validated transports into stored transaction events and receipt delivery.
GitHub CI verifies source and builds unsigned APKs. Only the separate release environment exposes signing material.

## 2. Trust Boundaries and Security Zones

- Untrusted inputs include QR payloads, imported catalogs, external payment answers, terminal discovery results,
  GitHub release metadata, browser form input and pull-request source.
- Device storage and application orchestration are trusted only while the device and its operating system are safe.
  Optional admin access and Manager approval protect different actions; approval is rechecked before financial work.
- Adyen service identities require HTTPS or authenticated terminal encryption and environment-specific validation.
  Stored original payment context must match current credentials before historical financial actions.
- Build jobs may execute repository code, plugins and dependencies. They must not receive signing secrets.
  Main-only artifact production, exact-checkout checks and successful job dependencies protect promotion.
- The signing job accepts a trusted CI artifact, verifies commit/version/run/hash facts and signs without Gradle.
  Pages deployment accepts only artifacts from a successful website-verification job on main.

## 3. Attack Surface Inventory

There is no public application HTTP server or file-upload endpoint. External interfaces are outbound Adyen HTTPS,
encrypted Terminal API, Payments app links, SMTP, optional GitHub release checks, QR import and Android file sharing.
The setup helper runs local browser code under a no-network Content Security Policy.
Repository interfaces include GitHub events, workflow inputs, API run metadata, downloaded artifacts and shell scripts.

## 4. Critical Assets and Data Classification

- Credentials: Adyen API keys, terminal shared keys, SMTP passwords, PIN verifiers, Android Keystore keys,
  APK signing keys and GitHub cache-encryption keys. Never commit or log their values.
- Personal data: shopper email/reference and receipt details, including masked card information.
  They remain in private device storage or deliberately delivered receipts, not telemetry or public CI fixtures.
- Financial state: operation identities, request facts, transaction history and encrypted recovery journals.
  Unknown outcomes are not failures; retries preserve the logical operation's stored identity.
- Deployment integrity: source commits, dependency graph, CI results, APK/tooling hashes, signatures and version codes.

## 5. Threat Analysis

Threats below describe attack patterns and accepted risks, not confirmed vulnerabilities.

| Component | Spoofing | Tampering | Repudiation | Disclosure | Denial of service | Privilege escalation |
| --- | --- | --- | --- | --- | --- | --- |
| UI and PINs | Impersonated operator | Changed approvals | Denied action | Exposed shopper details | Repeated PIN attempts | Manager/admin bypass |
| Storage and recovery | Wrong payment context | Changed journal/state | Missing operation facts | Device secret extraction | Broken recovery blocks checkout | Direct mutation bypass |
| Core formats and imports | Forged transfer source | Malformed money/QR/catalog | Lost import identity | Transfer code and QR disclosure | Huge payload or KDF count | Activation before verification |
| Adyen transport | Forged service/reply | Modified encrypted answer | Ambiguous transmission | Credential/request logging | Timeout/retry storms | Wrong environment/account access |
| Receipts and SMTP | Wrong recipient | Changed receipt fields | Delivery ambiguity | Overbroad file grants | Oversized rendering | Share private storage |
| Website and helper | Replaced helper | Script injection | Untracked publication | Browser credential leakage | Stalled generation | Network policy bypass |
| Tooling and CI | Forged successful run | Changed artifact/hash | Missing provenance | Signing/cache secret leakage | Duplicate/unbounded jobs | PR artifact promotion |

Existing mitigations include typed validation, Long minor-unit money, bounded transfer KDF counts, authenticated
encryption, Adyen certificate validation, request matching, persisted operation facts, idempotent recovery,
parameterized Room access, PIN lockouts, narrow file sharing, offline helper CSP, pinned actions and binary checksums.
CI retains lint, coverage, architecture, API-level, browser and release-integrity checks. Workflow expressions enter
shell scripts through environment variables; verified numeric run identities are never shell-evaluated.

High-impact patterns are forged financial replies, unsigned/unverified deployment artifacts and approval bypass.
Likelihood depends on device, credential or repository compromise and is not quantified by this local review.
Financial spoofing/tampering/escalation can be critical; disclosure and signing compromise can be high severity;
availability and provenance failures are generally medium. Defense-in-depth remains necessary at every boundary.

Known gaps are documented real-Adyen verification gaps and compromise of an unlocked device.
Local encryption is not protection against a fully compromised device. CI simulator tests do not prove real API behavior.
No new logging, backend or remote credential probes should be added as a mitigation without a separate design decision.

## 6. Vulnerability Pattern Library

Use Room parameters instead of interpolating shopper values into SQL. Escape browser text instead of using
`innerHTML` with imported values. Keep imported paths out of filesystem operations and restrict file-provider roots.
Never shell-evaluate workflow input, signing properties or external API data:

```sh
# Unsafe pattern: eval "$EXTERNAL_VALUE"
tool --commit "$COMMIT"
```

Authentication bypass patterns include accepting mismatched request identities, probing keys across environments
or skipping transfer verification before activation. Object-access bypass patterns include refunding a stored
payment without checking its original context and current permitted actions.
Release spoofing patterns include accepting PR, incomplete or failed runs, or trusting an artifact name without
commit/version/run-attempt/hash checks. Guard signing behind successful verified CI and keep plugins away from secrets.

## 7. Security Testing Strategy

Run the complete quality gate and R8 build. Retain architecture deliberate-violation tests, crypto vectors,
transport fakes, recovery/idempotency tests, browser CSP/transfer tests, coverage thresholds and API-level checks.
Exercise actual shell reuse logic with fake GitHub commands, including failed API reads, missing and tampered artifacts.
Verify both CodeQL languages after deployment and compare explicit resolved dependency graphs before disabling duplicates.
Real Adyen calls require separate explicit authorization and must never be inferred from simulator success.

## 8. Assumptions and Accepted Risks

This model relies on repository instructions, CONTEXT, SECURITY and contributor documentation plus changed-code review.
It is not a full repository penetration test. No compliance framework or security contact has been specified.
Device-resident API keys, optional PINs, explicitly selected plaintext SMTP and manual authoritative reconciliation
in the Customer Area are documented deployment trade-offs. GitHub administrators, main and signing-environment
controls remain trusted. Cache writes stay scoped to trusted main builds; forks do not receive secrets.

## 9. Version Changelog

1.0.0: Initial local model for build-workflow deployment and pre-commit security analysis.
