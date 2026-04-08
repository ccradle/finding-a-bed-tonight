# DV Opaque Referral — Privacy-Preserving Shelter Referral

## Purpose

This document describes how Finding A Bed Tonight (FABT) enables referrals to domestic violence (DV) shelters without storing personally identifying information (PII) about survivors. It is intended for CoC administrators, DV shelter operators, and auditors evaluating FABT's compliance with federal and state confidentiality requirements.

---

## Important Notice

This document describes the architectural and operational design of FABT's DV referral feature as it relates to applicable federal and state confidentiality requirements. It is intended as a technical reference for evaluation purposes only.

**This document does not constitute legal advice.** Organizations deploying FABT for domestic violence shelter referrals should consult qualified legal counsel regarding their specific compliance obligations under applicable federal, state, and local law. Confidentiality requirements vary by jurisdiction and may be more stringent than the federal baseline described here. The legal citations and statutory summaries in this document reflect the authors' understanding at the time of writing and should be independently verified before reliance.

---

## Legal Basis

### Federal Requirements

| Law | Key Requirement | FABT Compliance |
|-----|----------------|-----------------|
| **VAWA** (34 U.S.C. 12291(b)(2)) | PII must not be disclosed "regardless of whether the information has been encoded, encrypted, hashed, or otherwise protected" | Referral tokens contain zero client PII. No client name, DOB, SSN, address, or phone is stored. |
| **FVPSA** (45 CFR Part 1370) | Shelter location may not be made public without written authorization | Shelter address is never displayed to referring workers. Shared verbally during warm handoff call only. |
| **HMIS Prohibition** (HUD) | Victim Service Providers (VSPs) are prohibited from entering survivor data into shared HMIS | FABT is not an HMIS. Referral tokens contain no survivor-identifying data. Aggregate counts only for HUD reporting. |
| **42 CFR Part 2** | Applies when DV shelters also provide substance abuse treatment — stricter than HIPAA | FABT stores no treatment information. Zero-PII design satisfies the strictest standard. |

### North Carolina (FABT's Primary Jurisdiction)

| Statute | Protection |
|---------|-----------|
| **G.S. 8-53.12** | Advocate-victim privilege — communications between DV advocate and victim are privileged |
| **Chapter 15C** | Address Confidentiality Program — survivors can use substitute address |
| **Chapter 50B** | Domestic Violence protective order framework |

### Other State Considerations

- **Texas** (Penal Code 42.075): Disclosing a DV shelter location is a **Class A misdemeanor** (up to 1 year jail). FABT never displays DV shelter addresses.
- **California** (Evidence Code 1037): Sexual assault/DV counselor-victim evidentiary privilege.
- **New York**: Prohibits revealing residential DV program addresses in any proceeding.
- **40+ states** have Address Confidentiality Programs (ACPs).

---

## Architecture: Opaque Referral Token

### How It Works

```
┌──────────────────┐     ┌─────────────┐     ┌──────────────────┐
│  Outreach Worker │     │    FABT     │     │  DV Shelter Staff │
│  (has dvAccess)  │     │   Server    │     │  (coordinator)    │
└────────┬─────────┘     └──────┬──────┘     └────────┬─────────┘
         │                      │                      │
         │  1. "Request         │                      │
         │     Referral"        │                      │
         │  (household size,    │                      │
         │   pop type, urgency, │                      │
         │   callback number)   │                      │
         │─────────────────────>│                      │
         │                      │                      │
         │                      │  2. Create token     │
         │                      │     (zero PII)       │
         │                      │                      │
         │                      │  3. Notify shelter   │
         │                      │     coordinator      │
         │                      │─────────────────────>│
         │                      │                      │
         │                      │  4. Shelter staff    │
         │                      │     screens referral │
         │                      │     (safety check)   │
         │                      │                      │
         │                      │  5. Accept/Reject    │
         │                      │<─────────────────────│
         │                      │                      │
         │  6. If accepted:     │                      │
         │     "Call shelter    │                      │
         │      intake at       │                      │
         │      919-555-XXXX"   │                      │
         │<─────────────────────│                      │
         │                      │                      │
         │  7. Worker calls     │                      │
         │     shelter directly │                      │
         │     (warm handoff)   │                      │
         │─────────────────────────────────────────────>│
         │                      │                      │
         │                      │  8. Token expires    │
         │                      │     and is PURGED    │
         │                      │     (hard delete)    │
```

### Key Design Principles

1. **Zero PII in the system**: The referral token contains household size, population type, urgency, special needs, and the *worker's* callback number. Never the client's name, DOB, SSN, address, or phone.

2. **Human-in-the-loop**: DV shelter staff must screen every referral. Automated placement is not possible because:
   - Safety screening is required (abuser infiltration risk)
   - Shelter staff assess fit (capacity, services, household needs)
   - VAWA requires informed consent for information sharing

3. **Warm handoff**: The shelter's physical address is shared verbally during a phone call, never displayed in FABT. The referring worker receives only the shelter's intake phone number.

4. **Token purge**: All terminal-state tokens (ACCEPTED, REJECTED, EXPIRED) are **hard-deleted** within 24 hours. No audit trail of individual referrals survives in the database.

5. **Aggregate analytics only**: Micrometer counters track referral counts (requested/accepted/rejected/expired) without any identifying data. These counters support HUD HIC/PIT reporting.

---

## What FABT Stores vs. What It Never Stores

| Stored | Never Stored |
|--------|-------------|
| Referral token ID (UUID) | Client name |
| Shelter ID (UUID, RLS-protected) | Client date of birth |
| Referring worker's user ID | Client SSN or government ID |
| Household size (integer) | Client address or phone |
| Population type (enum) | Client photo or biometric data |
| Urgency level (STANDARD/URGENT/EMERGENCY) | Which client went to which shelter |
| Special needs (free text — wheelchair, pets, medical) | Treatment or diagnosis information |
| Worker's callback number | Shelter street address (to referring worker) |
| Token status (PENDING/ACCEPTED/REJECTED/EXPIRED) | |
| Timestamps (created, responded, expires) | |
| Rejection reason (optional, no PII advisory) | |
| Aggregate counters (Micrometer) | |

**Even if the database is compromised**, an attacker learns only: "an outreach worker requested a DV bed at time T for a household of size N." The architecture is designed to minimize re-identification risk — no names, DOB, SSN, or addresses are stored. Correlation attacks against external data sources remain a residual risk that organizations should assess independently.

> **Free-text field risk note:** The "Special needs" field accepts free text. While the UI displays an advisory — "Do not include client identifying information" — no automated scrubbing or validation prevents a coordinator from typing PII into this field. CoC administrators should include this field in staff training: only operational descriptors (e.g., "wheelchair," "service dog," "requires ground floor") should be entered. FABT does not guarantee that this field is PII-free at the time of token purge. The 24-hour hard deletion mitigates the exposure window, but does not eliminate the risk entirely if PII is entered contrary to the advisory.

---

## VAWA Compliance Checklist

CoC administrators deploying FABT can use this checklist for self-assessment:

- [ ] DV shelters are marked `dvShelter=true` in FABT
- [ ] Only users with `dvAccess=true` can see DV shelters in search results (RLS enforced)
- [ ] Referral tokens contain no client PII (verify by inspecting `referral_token` table schema)
- [ ] DV shelter addresses are not displayed to referring workers (verify UI and API responses)
- [ ] Shelter intake phone number is shared only after acceptance (warm handoff)
- [ ] Token purge is running (check logs for "Purged N DV referral tokens")
- [ ] No individual referral records survive beyond 24 hours of terminal status
- [ ] Aggregate analytics contain only counts, no identifying data
- [ ] Rejection reasons do not contain client PII (advisory label shown to shelter staff)
- [ ] Consent is obtained verbally during the warm handoff call, not through FABT

  > **Why verbal consent at warm handoff satisfies VAWA:** VAWA's written consent requirements apply to disclosures of victim information to outside entities. The FABT warm handoff is not such a disclosure — the shelter's intake phone number is shared with the outreach worker who is actively facilitating the client's placement, not forwarded to a third party. The outreach worker then calls the shelter directly — functionally the same as the worker calling the shelter without any platform intermediary. No survivor-identifying information is shared in either direction through the FABT system. Consent for shelter placement is obtained by the outreach worker and shelter staff during the warm handoff call itself, consistent with standard coordinated entry practice. Organizations with specific consent policy requirements should consult their VAWA administrator or legal counsel.

---

## Analytics and Reporting

### What CoCs Can Report to HUD

FABT supports aggregate DV reporting without violating confidentiality:

- **Housing Inventory Count (HIC)**: Total DV beds by population type (from `bed_availability` snapshots)
- **Point-in-Time (PIT)**: Current DV bed utilization (occupied/available counts)
- **Referral volume**: Requested, accepted, rejected, expired counts per time period
- **Response time**: Average minutes from referral request to shelter response

### Analytics Durability

DV referral analytics are backed by Micrometer counters (`fabt_dv_referral_total{status=...}`).

- **With observability stack** (`--observability`): Prometheus scrapes and retains counter values long-term. Analytics survive backend restarts and provide historical data.
- **Without observability stack** (Lite tier): Counters are in-memory and reset on backend restart. This is a known limitation. **CoCs requiring durable DV referral analytics must enable the observability package.**

---

## Operational Notes

### Grafana Dashboard

A separate **FABT DV Referrals** Grafana dashboard (`fabt-dv-referrals`) is provisioned alongside the main FABT Operations dashboard when the observability stack is active (`--observability`).

**Why separate?** DV referral volume patterns are sensitive even in aggregate. A CoC may want to grant Grafana access to their operations team for the main dashboard without exposing DV referral data. Separate dashboards support separate access controls in Grafana.

**Panels:**
| Panel | Metric | Purpose |
|-------|--------|---------|
| Referral Request Rate | `rate(fabt_dv_referral_total{status="requested"})` | Volume of incoming referrals |
| Acceptance Rate | accepted/requested % | Are shelters accepting referrals? |
| Avg Response Time | `fabt_dv_referral_response_seconds` | How fast do shelters respond? |
| Rejection Rate | `rate(fabt_dv_referral_total{status="rejected"})` | Are referrals being declined? |
| Expired Rate | `rate(fabt_dv_referral_total{status="expired"})` | High rate = shelters not responding |
| Referral Totals | `fabt_dv_referral_total` by status | Overall counts |

### Address Visibility Policy

DV shelter addresses are redacted in API responses based on a configurable tenant-level policy (`dv_address_visibility` in tenant config JSONB).

| Policy | Who Sees Address | Use Case |
|--------|-----------------|----------|
| `ADMIN_AND_ASSIGNED` (default) | PLATFORM_ADMIN, COC_ADMIN, coordinators assigned to the shelter | Most deployments |
| `ADMIN_ONLY` | PLATFORM_ADMIN, COC_ADMIN only | Stricter — even assigned coordinators don't see address in API |
| `ALL_DV_ACCESS` | Any user with dvAccess=true | Permissive / legacy behavior |
| `NONE` | No one sees address in API | Maximum restriction — address only via verbal handoff |

Change the policy via API (PLATFORM_ADMIN only):
```bash
curl -X PUT http://localhost:8080/api/v1/tenants/<id>/dv-address-policy \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Confirm-Policy-Change: CONFIRM" \
  -H "Content-Type: application/json" \
  -d '{"policy": "ADMIN_AND_ASSIGNED"}'
```

**IMPORTANT:** This endpoint should not be exposed outside the corporate firewall. It requires PLATFORM_ADMIN role and a confirmation header to prevent accidental invocation.

### Token Expiry

Default: 4 hours (`dv_referral_expiry_minutes: 240` in tenant config). Configurable per tenant without restart (cached with 60-second refresh).

### Token Purge

`ReferralTokenPurgeService` runs every hour. Hard-deletes tokens in terminal state (ACCEPTED/REJECTED/EXPIRED) older than 24 hours. Monitor via log line: `"Purged N DV referral tokens (ACCEPTED: X, REJECTED: Y, EXPIRED: Z)"`.

### Row Level Security

The `referral_token` table inherits DV shelter access control via the `shelter` FK join. The RLS policy follows the same pattern as `bed_availability` (V13). Users without `dvAccess=true` cannot see, create, or respond to DV referral tokens.

### Defense in Depth (D14)

DV shelter protection is enforced at **two independent layers**:

1. **Database layer (RLS)**: Every JDBC connection executes `SET ROLE fabt_app` before any query. The `fabt_app` role is NOSUPERUSER (created in V16), so PostgreSQL RLS policies enforce. The session variable `app.dv_access` is set from the JWT claim. Users without `dvAccess=true` cannot see DV shelter rows — the database simply returns no results.

2. **Service layer**: `ReferralTokenService.createToken()` explicitly checks `TenantContext.getDvAccess()` and throws `AccessDeniedException` if false. This check is independent of RLS — even if the database role were misconfigured, the service layer blocks the request.

**Why both?** During development, we discovered that PostgreSQL superusers (including the user created by Testcontainers in CI) bypass RLS entirely, even with `FORCE ROW LEVEL SECURITY`. The `SET ROLE` fix ensures RLS applies in all environments. The service-layer check ensures protection even if the database configuration is wrong. Neither layer alone is sufficient — together they provide the zero-tolerance protection required by VAWA.

### Offline Behavior (v0.25.1)

DV referral requests are **intentionally NOT queued offline**. This is a security decision:

- **What happens offline:** The "Request Referral" button becomes visually muted (`aria-disabled`) and does not open the referral modal. An inline message shows the shelter's phone number as a clickable `tel:` link: "Referral requests need a connection. Call [phone] to request a referral by phone."
- **Why not queue?** DV referrals contain sensitive operational data (callback number, household size, special needs, urgency). Storing this in browser IndexedDB on a worker's device creates a risk: a lost, seized, or shared device could expose information that helps an abuser identify a survivor's situation. The zero-PII design depends on referral data living on the server briefly and being hard-deleted within 24 hours — persisting on-device undermines this.
- **Sector precedent:** No DV service platform has a documented offline referral workflow (confirmed by NNEDV Safety Net review, 2026). HMIS systems and 211 platforms are entirely server-dependent with no offline referral capability.
- **Fallback:** Workers call the DV shelter directly. The phone number is included in search results (not redacted — only the address is).
- **Second line of defense:** If `navigator.onLine` lies (captive portals), the referral modal opens but submit fails with an error rendered inside the modal (not behind it). The worker sees "Could not reach the server. Check your connection and try again."
