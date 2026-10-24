---
name: minimpos-security-review
description: Review a Mini mPOS change or branch for security issues against the STRIDE threat model, and keep the
  model current.
---

# Mini mPOS security review

The [threat model](../../../security/threat-model.md) owns trust boundaries, assets, threats, the vulnerability pattern
library and accepted risks. [SECURITY.md](../../../SECURITY.md) owns reporting and data protection. Read both first,
then the module rules for the changed code.

## Scope

- Review the diff against its merge base (`git diff --merge-base main`), or the working tree for uncommitted changes.
  Read enough surrounding code to follow each changed data flow across a trust boundary; this is a change review, not
  a full repository audit.
- Check every changed input path against the pattern library: SQL built from shopper or imported values, browser
  `innerHTML`, filesystem paths from imports, shell evaluation of workflow, signing or API data, identity and
  environment checks, original payment context, and release artifact verification.
- Check the non-negotiable constraints in [AGENTS.md](../../../AGENTS.md): secrets only through `SecretStore`, no
  logging of secrets, payment data or requests, permissions, persistence before sending, and the setup gate.

## Findings

- Classify each finding by STRIDE category and severity (CRITICAL, HIGH, MEDIUM, LOW). CRITICAL blocks the change;
  HIGH and CRITICAL need the maintainer's review before merging.
- Give each one a file and line, a concrete failure scenario and a fix. Drop speculation you cannot tie to a reachable
  path; list what you could not verify (real Adyen behavior, devices, deployed hosting) as limitations.
- Report the result in the pull request or the conversation. Do not commit scan results.

## Keeping the model current

When a change adds an input, credential, endpoint, permission, storage, workflow job or release step, update the
affected sections of the threat model in the same change, bump its version and add a changelog entry.
