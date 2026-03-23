# DV Opaque Referral — Privacy-Preserving Shelter Referral

## Purpose

This document describes how Finding A Bed Tonight (FABT) enables referrals to domestic violence (DV) shelters without storing personally identifying information (PII) about survivors. It is intended for CoC administrators, DV shelter operators, and auditors evaluating FABT's compliance with federal and state confidentiality requirements.

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

**Even if the database is compromised**, an attacker learns only: "an outreach worker requested a DV bed at time T for a household of size N." There is no way to identify the survivor.

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
