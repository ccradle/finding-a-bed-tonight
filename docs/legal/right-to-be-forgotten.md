# Right to be Forgotten — Data-Subject Deletion Posture

> **AI-Generated Document:** This document was drafted with AI assistance based on FABT's source code, schema, and the project's `feedback_truthfulness_above_all` discipline. It is not legal advice and does not constitute a compliance determination. Deployment owners should review with qualified legal counsel before adopting it as the authoritative posture for their jurisdiction.

## 1. Scope

FABT (Finding A Bed Tonight) is **self-hosted, open-source software** distributed under Apache 2.0. The FABT project is the *software author*, not the *data controller*. Each deployment is operated by a Continuum of Care, city, hospital, or other organization (the **deployment owner**) on infrastructure they themselves provision. Client data flowing through any FABT instance is held by the deployment owner; the project receives nothing.

A right-to-be-forgotten (RTBF) request — whether under VAWA / FVPSA confidentiality, GDPR Art. 17, CCPA §1798.105, or any other jurisdiction's analog — is therefore a request to the deployment owner, not to the FABT project. This document describes the platform's automated retention behavior and the runbook the deployment owner uses when an ad-hoc deletion request arrives.

## 2. Automated retention vs ad-hoc deletion

FABT's automated retention is NOT a substitute for an ad-hoc data-subject deletion request. The two operate on different surfaces:

- **Automated retention floors** apply to specific data classes regardless of subject identity:
  - DV referral tokens — hard-deleted no later than 24 hours after a terminal status (DV path is zero-PII; the token is the only artifact)
  - Hold-attribution PII (v0.55+) — `held_for_client_name_encrypted`, `held_for_client_dob_encrypted`, `hold_notes_encrypted` columns on `reservation` are nulled no later than 25 hours after a reservation reaches a terminal status (CANCELLED / CONFIRMED / EXPIRED / CANCELLED_SHELTER_DEACTIVATED)
  - At-rest encryption — per-tenant DEK (`tenant_dek.purpose='RESERVATION_PII'`) means hard-deleting the tenant via Phase F-6 renders all encrypted PII unrecoverable (crypto-shred)
- **Ad-hoc deletion** is the deployment owner's responsibility. Automated retention bounds the WORST-CASE lifetime of in-scope data; it does NOT promise that a specific subject's information will be expunged faster than the SLA on operator demand.

If a subject submits a deletion request to the deployment owner, the deployment owner SHOULD NOT wait for the automated purge — they should follow §4 below.

## 3. Jurisdictional reach (no position taken at v0.55)

The FABT project does NOT take a position on GDPR (EU/EEA), CCPA/CPRA (California), VCDPA (Virginia), CPA (Colorado), CTDPA (Connecticut), UCPA (Utah), HIPAA Security/Privacy Rule, FERPA, or any other jurisdictional data-subject-rights framework. Whether a given FABT deployment is subject to any of these is a determination only the deployment owner's qualified legal counsel can make, based on:
- Where the deployment is hosted
- Where data subjects reside
- The deployment owner's role (covered entity, business associate, data controller, processor)
- The intended use case (DV referral, reentry navigation, hospital discharge, etc.)

The platform is *designed to support* a deployment owner who needs to respond to such requests; it does not represent itself as conforming to any specific framework absent independent certification.

## 4. Deployment-owner runbook — handling a deletion request

When a deletion request arrives, the deployment owner SHOULD work through these steps. This runbook is operator-readable; it names tables and columns but does not assume engineering depth.

### 4.1 Verify request authenticity

Before acting, confirm the requester is who they claim to be. The platform records the requester's user account (if they have one) but does NOT independently verify identity. The deployment owner's own identity-verification policy applies.

### 4.2 Enumerate the subject's data on the deployment

Run these SQL queries (as the `fabt` database owner — not `fabt_app` — so RLS doesn't hide DV-shelter rows). All queries scope to the deployment owner's tenant; the platform is multi-tenant and other tenants' data is isolated.

```sql
-- (a) The subject as a platform user (if applicable)
SELECT id, email, display_name, role, tenant_id, created_at
FROM app_user
WHERE email = '<subject-email>'
   OR display_name ILIKE '%<subject-name>%';

-- (b) Reservations the subject created (as a navigator/outreach worker)
SELECT id, shelter_id, status, created_at,
       (held_for_client_name_encrypted IS NOT NULL) AS has_attribution_pii,
       expires_at, updated_at
FROM reservation
WHERE user_id = '<subject-user-id>';

-- (c) Reservations where the subject is the client (hold attribution)
-- NOTE: this requires DECRYPTING the encrypted columns to match by name/DOB.
-- The platform does NOT provide a search-by-decrypted-PII API; the
-- deployment owner must run a one-off decryption pass or wait for the
-- automated purge (worst case 25 hours after the hold's terminal status).
-- See "Why we don't index encrypted PII" in design D4 of
-- transitional-reentry-support.

-- (d) Audit-event rows where the subject is the actor or target
SELECT timestamp, event_type, actor_user_id, target_user_id, details
FROM audit_events
WHERE actor_user_id = '<subject-user-id>'
   OR target_user_id = '<subject-user-id>'
ORDER BY timestamp DESC
LIMIT 200;
```

### 4.3 Determine the right action

- **If the subject is a platform user** (CoC admin, coordinator, outreach worker, navigator): the deployment owner can deactivate the user account immediately (`app_user.active = false` on the admin Users tab). Audit-event rows referencing them are NOT deleted — those are the audit trail of *what they did*, not personal data per se. Consult counsel on whether your jurisdiction treats audit actor-id as deletable PII.
- **If the subject is a hold-attribution client** (their name or DOB was entered on a `reservation` row): the automated purge will erase the encrypted columns within 25 hours of terminal status. If the request is "delete now, do not wait for the SLA," the deployment owner must run a manual `UPDATE reservation SET held_for_client_name_encrypted = NULL, held_for_client_dob_encrypted = NULL, hold_notes_encrypted = NULL WHERE id = '<reservation-id>'` AND emit a `RESERVATION_PII_PURGED` audit row matching the schema in §13 of `reentry-release-readiness`.
- **If the request is for full tenant offboarding** (the entire deployment must be erased): use the Phase F-6 tenant hard-delete + crypto-shred workflow. See `docs/security/crypto-shred-runbook.md` and the F-6 design in `multi-tenant-production-readiness/design-f6-real-cryptoshred.md`. Hard-deleting a tenant removes both `tenant_dek` rows (TENANT_DATA + RESERVATION_PII) and all per-tenant data, rendering encrypted columns unrecoverable.

### 4.4 Document the response

Record the request, the verification steps taken, the action chosen, and the outcome in the deployment owner's own retention log. The platform's audit_events table captures the SQL actions taken; it does NOT capture the original request, the verification outcome, or the deployment owner's policy decision. That documentation is the deployment owner's responsibility.

## 5. What the platform does NOT promise

- The platform does NOT receive data-subject requests. There is no `/dsr` endpoint, no contact form, no email alias the FABT project monitors for these.
- The platform does NOT pre-emptively erase data on subject demand. Automated retention is the floor; ad-hoc deletion is operator-driven.
- The platform does NOT certify compliance with any jurisdictional framework. "Designed to support" is the project's claim; "compliant with" is a deployment-owner determination.
- The platform does NOT delete audit-event rows. The audit trail is append-only by design (see `docs/security/compliance-posture-matrix.md` "audit_events — what a row means"). Whether a jurisdiction permits redacting actor identifiers from audit rows is a deployment-owner question.

## 6. Tracking

This document is the v0.55 minimal posture. A fuller treatment — explicit GDPR Art. 17 lawful-basis matrix, CCPA §1798.105 exception map, HIPAA §164.526 amendment-vs-deletion clarification — is deferred to v0.56 alongside `PRIVACY.md` (target Q2-2026). Until then, this file is the authoritative starting point for a deployment owner facing a deletion request.

## See also

- `docs/security/compliance-posture-matrix.md` — full retention contract per data class, including the 25-hour purge SLA operational signal
- `docs/security/crypto-shred-runbook.md` — Phase F-6 tenant hard-delete + DEK crypto-shred procedure
- `docs/government-adoption-guide.md` — DV protection scope, opaque-token referral path, navigator-hold opt-in PII path
- `docs/hospital-privacy-summary.md` — two-column data-path table for hospital privacy officers
- `docs/operations/reentry-mode-user-guide.md` §5 — operator view of the 25-hour purge + audit-event lifecycle

---

*FABT — Right-to-be-Forgotten Posture (v0.55)*
*This document does not constitute legal advice. Review with qualified counsel before adopting as authoritative.*
