# Casey Review Checklist — multi-tenant-production-readiness Phase 0 (PR #127)

> Purpose: a discoverable index of every legal-charged surface this PR adds, so
> Casey's review pass is concrete instead of "spot-check the diff." Patterns
> watched: bare compliance claims (`compliant`, `compliance with`),
> equivalence claims (`equivalent to`, `same as <regulation>`), absolute
> guarantees (`guarantees`, `ensures legal`), commercial-product equivalence
> (`HIPAA-compliant database`).
>
> CI's `legal-language-scan.sh` already gates against the bare keywords. This
> checklist surfaces the **contextual** lines where the keyword is allow-listed
> or absent but where Casey may still want a wording tweak.

## Surfaces added in PR #127

### 1. `docs/architecture/tenancy-model.md` (NEW ADR)

Legal-adjacent terms used:

| Line | Term | Context | Casey check |
|---|---|---|---|
| 11 | `HIPAA BAA / VAWA-confidentiality / data-residency requirements` | List of triggers that route to silo tier | Confirm "VAWA-confidentiality" is the right construct (vs. "VAWA § 40002(b)(2) confidentiality"). |
| 13 | `regulated CoCs (HIPAA BAA, VAWA-exposed DV CoCs ...)` | Silo tier criteria | "VAWA-exposed" — is this a recognised term, or should it be "VAWA-covered" / "VAWA-applicable"? |
| 21 | `Pooled is not categorically prohibited by HIPAA, but most BAA-class procurement reviews prefer the stronger isolation model.` | Tier-routing rationale | Negative phrasing ("not prohibited") chosen deliberately to avoid claiming HIPAA blessing. Confirm tone. |
| 22 | `VAWA § 40002(b)(2) + FVPSA confidentiality + the "Comparable Database" construct` | Citation chain | Verify § 40002(b)(2) is the correct citation. "Comparable Database" in quotes — referring to the VAWA construct, not claiming we ARE one. |
| 80 | `Notification SLAs: HIPAA 60 days for individuals / HHS; VAWA 24 hours to OVW; GDPR 72 hours to DPA` | Breach-notification timing | Factual SLA recitation. Confirm the day counts are correct as currently written. |

Sign-off line in the file: "Casey Drummond (legal lens) — HIPAA BAA and VAWA Comparable Database obligations route to silo; pooled tier is defensible for standard tier."

**Does Casey approve this attribution?** If yes, leave as-is. If no, the attribution should be removed or reworded.

### 2. `docs/security/timing-attack-acceptance.md` (NEW ADR)

Legal-adjacent terms used:

| Line | Term | Context | Casey check |
|---|---|---|---|
| 30 | `endpoint that follows the D3 envelope contract` | Was originally "D3-compliant endpoint"; reworded after CI scan flagged "compliant" | Confirm rewording reads naturally and doesn't create a different overclaim. |
| 47–53 | "Revisit conditions" | Lists triggers like "regulated-tier pilot whose compliance team requires mitigation as a contractual obligation" | "compliance team" used as a noun (the team), not a claim. Casey: confirm. |
| 60–62 | Sign-offs from Marcus / Alex / Riley | No Casey sign-off claimed | Casey: confirm comfort that no legal sign-off is asserted. |

### 3. `backend/src/main/java/org/fabt/auth/service/DynamicClientRegistrationSource.java`

Single legal-adjacent token:

| Line | Term | Context | Casey check |
|---|---|---|---|
| 140 | `// Keycloak and other OIDC-compliant providers` | Code comment about which OIDC URL pattern to apply | "OIDC-compliant" used in the technical-spec sense (RFC compliance), not regulatory compliance. Allow-listed by `.legal-allowlist` historically. Casey: confirm not in scope of the legal scanner intent. |

### 4. `backend/src/main/java/org/fabt/shared/security/SecretEncryptionService.java`

No legal-charged terms in the modified constructor (verified via grep). Documentation strings refer to AES-GCM mechanics only.

### 5. `backend/src/main/java/org/fabt/auth/service/TenantOAuth2ProviderService.java`

No legal-charged terms (verified). Encryption call site is a technical description.

### 6. `backend/src/main/java/org/fabt/hmis/service/HmisConfigService.java`

No legal-charged terms (verified). New helper docstrings describe encryption helpers and plaintext-tolerant fallback.

### 7. `backend/src/main/java/db/migration/V59__reencrypt_plaintext_credentials.java`

No legal-charged terms (verified). Class Javadoc describes the migration's idempotency contract and Flyway placement rationale only.

## What Casey does NOT need to scan in this PR

- Test files (`backend/src/test/java/**`) — internal-only, never reach a customer or auditor surface.
- `CHANGELOG.md` — describes the technical change, no compliance claims.
- `openspec/changes/multi-tenant-production-readiness/proposal.md` + `design.md` — internal architecture docs, not customer-facing. Casey will get the customer-facing legal docs (`docs/legal/*`) when Phase H of the change ships.

## Scope-out reminder

This PR is **Phase 0** — encryption foundation only. The compliance surfaces
that need Casey's full review (BAA template, per-tenant BAA registry, VAWA
breach runbook, comparable-database doc, DV-safe breach notification, data-custody
matrix, contract clauses, children-data carve-out) all land in **Phase H**
(tasks 9.1–9.15 in `tasks.md`). This checklist exists so Casey can confirm
Phase 0 doesn't accidentally make claims that pre-empt those Phase H artifacts.

## How to give the verdict

- Drop a PR review comment on #127 with one of:
  - **APPROVE** — wording is fine, ship as-is
  - **REQUEST CHANGES — line N: <suggested rewrite>**
  - **NEEDS DISCUSSION** — schedule a 15-min sync before approving

If approving, also add Casey's sign-off line to `tenancy-model.md` if she agrees
with the attribution as written; otherwise propose the rewording in the PR review.
