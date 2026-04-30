# Hospital Privacy Summary — Finding A Bed Tonight

> **AI-Generated Document:** This summary was produced by an AI assistant (Claude) based on published HIPAA guidance from HHS.gov and FABT's two-path data architecture (zero-PII DV referral path + opt-in encrypted-and-purged non-DV navigator-hold path). It should be reviewed by your organization's privacy officer and qualified legal counsel. This document does not constitute legal advice or a HIPAA compliance determination.

## For Hospital Privacy Officers

**Finding A Bed Tonight (FABT) stores no Protected Health Information (PHI) on the DV referral path. The non-DV navigator-hold path (introduced in v0.55) accepts optional, opt-in client identifiers; whether these constitute PHI in your hospital's workflow is a determination for your privacy officer.**

This document is intended for hospital privacy officers evaluating whether FABT requires a Business Associate Agreement (BAA) or HIPAA security risk assessment when used by hospital social workers for discharge planning. The two data paths have materially different privacy postures — see "What FABT Stores by Data Path" and "Business Associate Agreement" below.

## What FABT Is

FABT is a bed availability platform used by social workers to find available emergency shelter beds for patients being discharged to shelter. The social worker searches for beds, holds a bed, and coordinates placement. The DV referral path requires no patient information at any step. The non-DV navigator-hold path (v0.55+) offers optional fields for client name, DOB, and notes to help shelter coordinators expect a specific arrival; whether to use these fields is a hospital policy decision (see Business Associate Agreement section).

## What FABT Stores

| Data Element | Example | PHI? |
|---|---|---|
| Household size | 3 | No |
| Population type | FAMILY_WITH_CHILDREN | No |
| Urgency level | URGENT | No |
| Worker callback number | 919-555-0101 (the worker's phone, not the patient's) | No |
| Reservation timestamp | 2026-03-25T14:30:00Z | No |
| Shelter name | Oak City Emergency Shelter | No |
| Bed count | 50 total, 38 occupied | No |

## What FABT Stores by Data Path

FABT exposes two distinct data paths. The DV referral path stores no client identifiers, ever. The non-DV navigator hold path (introduced in v0.55 for reentry housing navigators and other third-party hold workflows) optionally accepts client identifiers — encrypted at rest, erased no later than 25 hours after the hold reaches a terminal status.

| HIPAA Identifier | DV referral path | Non-DV navigator-hold path |
|---|---|---|
| Name | **Never** | Optional; encrypted at rest with per-tenant DEK; erased ≤25 hours after hold ends |
| Date of birth | **Never** | Optional; encrypted at rest with per-tenant DEK; erased ≤25 hours after hold ends |
| Free-text notes | **Never** | Optional; encrypted at rest with per-tenant DEK; erased ≤25 hours after hold ends. Operators may enter content; your organization's policy decides what is appropriate. |
| Social Security number | **Never** | **Never** |
| Address (patient) | **Never** | **Never** |
| Phone number (patient) | **Never** | **Never** |
| Medical record number | **Never** | **Never** |
| Health plan number | **Never** | **Never** |
| Account number | **Never** | **Never** |
| Certificate/license number | **Never** | **Never** |
| Vehicle/device serial number | **Never** | **Never** |
| Web URL / IP address (patient) | **Never** | **Never** |
| Biometric identifier | **Never** | **Never** |
| Photograph | **Never** | **Never** |
| Diagnosis or treatment information | **Never** | **Never** |
| Insurance information | **Never** | **Never** |

**Path selection is structural, not policy.** The V91 CHECK constraint `shelter_dv_implies_dv_type` enforces that any shelter flagged `dv_shelter=true` must carry `shelter_type='DV'`, so DV-flagged inventory is unreachable through the navigator hold path. The optional hold-attribution fields are available only on non-DV holds.

## Business Associate Agreement

**No BAA is typically required if the hospital workflow uses zero opt-in PII fields.**

Per HHS guidance (45 CFR 160.103), a Business Associate Agreement is required only when a third party "creates, receives, maintains, or transmits" Protected Health Information on behalf of a covered entity. When a hospital social worker uses FABT WITHOUT entering any patient information into the optional hold-attribution fields (client name, DOB, notes), the system contains only operational data about shelter bed availability — no patient information of any kind — and a BAA is typically not necessary.

**The hospital privacy officer should review the platform's data flow before any hospital-employed user creates an account**, regardless of whether the opt-in fields are used. Incidental metadata from a covered-entity workforce member (login timestamps, hospital-network IP address, audit-trail records) can independently trigger BAA review depending on your organization's interpretation of the HIPAA Security Rule.

**If the hospital chooses to enter patient name, DOB, or free-text notes into a hold:** this is a HIPAA conversation your hospital privacy officer must own. The optional fields are encrypted at rest with a per-tenant DEK (`tenant_dek.purpose='RESERVATION_PII'`) and erased no later than 25 hours after the hold reaches a terminal status, but ANY entry of patient-identifying information by a covered-entity workforce member converts FABT's role into "receives PHI on behalf of the covered entity" — which is the trigger condition for a BAA. The hospital privacy officer must decide whether to (a) prohibit use of the optional fields by hospital staff, (b) permit them under a BAA executed with the FABT deployment owner, or (c) use a different platform.

This is a determination only your hospital's privacy officer can make, in consultation with qualified legal counsel and with knowledge of your organization's specific HIPAA risk posture.

## How Social Workers Use FABT

1. Social worker logs into FABT with their own credentials (not the patient's)
2. Searches for available beds by population type and constraints
3. Holds a bed (creates a reservation with household size and urgency — no patient info)
4. Coordinates transport and arrival with the shelter coordinator
5. Confirms or cancels the hold

At no point does the patient's name, medical information, or any identifying data enter the system.

## DV Shelter Referrals

For domestic violence shelter referrals, FABT uses an opaque token system designed to support VAWA/FVPSA requirements:
- Referral tokens contain only household size, urgency, and the worker's callback number
- Shelter address is shared verbally during a warm handoff (never in the system)
- Tokens are hard-deleted within 24 hours of terminal status

## Network Security

- FABT connects to shelter data via HTTPS
- No inbound connections to hospital systems are required
- No integration with EHR, EMR, or hospital information systems
- The application runs on infrastructure external to the hospital network
- The social worker accesses FABT via a web browser — no software installation required
- Authentication endpoints are rate-limited to prevent brute force attacks
- If the hospital requires SSO via their identity provider: password-authenticated users continue working independently of any SSO outage. The two authentication paths are architecturally separate. See the operational runbook for details.

## Data Lifecycle

- Bed availability snapshots are append-only operational records
- Reservations are operational records with no patient linkage
- DV referral tokens are hard-deleted within 24 hours
- No persistent link exists between a hospital patient and a shelter placement

## Summary Statement

> Finding A Bed Tonight is an operational bed availability tool used by social workers. The DV referral path stores no Protected Health Information as defined by 45 CFR 160.103, ever. The non-DV navigator-hold path (v0.55+) optionally collects client name, DOB, and free-text notes — encrypted at rest with a per-tenant DEK and erased no later than 25 hours after a hold ends. Whether the optional fields constitute PHI in your hospital workflow, and whether their use requires a Business Associate Agreement, is a determination your hospital privacy officer must make based on your organization's HIPAA risk posture.

## Sources

- [Business Associates FAQ — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/faq/business-associates/index.html)
- [Covered Entities and Business Associates — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/covered-entities/index.html)
- [Summary of the HIPAA Privacy Rule — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/privacy/laws-regulations/index.html)
- [HIPAA and Social Services — Network for Public Health Law](https://www.networkforphl.org/news-insights/the-largely-unknown-hipaa-privacy-rule-provision-that-speeds-access-to-social-services/)

---

*Finding A Bed Tonight — Hospital Privacy Summary*
*AI-generated document. Review by your organization's privacy officer and legal counsel recommended.*
