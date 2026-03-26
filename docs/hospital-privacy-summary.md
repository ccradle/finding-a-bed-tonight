# Hospital Privacy Summary — Finding A Bed Tonight

> **AI-Generated Document:** This summary was produced by an AI assistant (Claude) based on published HIPAA guidance from HHS.gov and FABT's zero-PII architecture. It should be reviewed by your organization's privacy officer and qualified legal counsel. This document does not constitute legal advice or a HIPAA compliance determination.

## For Hospital Privacy Officers

**Finding A Bed Tonight (FABT) stores no Protected Health Information (PHI).**

This document is intended for hospital privacy officers evaluating whether FABT requires a Business Associate Agreement (BAA) or HIPAA security risk assessment when used by hospital social workers for discharge planning.

## What FABT Is

FABT is a bed availability platform used by social workers to find available emergency shelter beds for patients being discharged to shelter. The social worker searches for beds, holds a bed, and coordinates placement — all without entering any patient information into the system.

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

## What FABT Never Stores

| HIPAA Identifier | Stored in FABT? |
|---|---|
| Name | **Never** |
| Date of birth | **Never** |
| Social Security number | **Never** |
| Address (patient) | **Never** |
| Phone number (patient) | **Never** |
| Medical record number | **Never** |
| Health plan number | **Never** |
| Account number | **Never** |
| Certificate/license number | **Never** |
| Vehicle/device serial number | **Never** |
| Web URL / IP address (patient) | **Never** |
| Biometric identifier | **Never** |
| Photograph | **Never** |
| Diagnosis or treatment information | **Never** |
| Insurance information | **Never** |
| Any of the 18 HIPAA identifiers | **Never** |

## Business Associate Agreement

**No BAA is required.**

Per HHS guidance (45 CFR 160.103), a Business Associate Agreement is required only when a third party "creates, receives, maintains, or transmits" Protected Health Information on behalf of a covered entity. FABT does not create, receive, maintain, or transmit any PHI. The system contains only operational data about shelter bed availability — no patient information of any kind.

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

## Data Lifecycle

- Bed availability snapshots are append-only operational records
- Reservations are operational records with no patient linkage
- DV referral tokens are hard-deleted within 24 hours
- No persistent link exists between a hospital patient and a shelter placement

## Summary Statement

> Finding A Bed Tonight is an operational bed availability tool used by social workers. It stores zero Protected Health Information as defined by 45 CFR 160.103. No Business Associate Agreement is required. No patient-identifying information enters the system at any point in the workflow.

## Sources

- [Business Associates FAQ — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/faq/business-associates/index.html)
- [Covered Entities and Business Associates — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/covered-entities/index.html)
- [Summary of the HIPAA Privacy Rule — HHS.gov](https://www.hhs.gov/hipaa/for-professionals/privacy/laws-regulations/index.html)
- [HIPAA and Social Services — Network for Public Health Law](https://www.networkforphl.org/news-insights/the-largely-unknown-hipaa-privacy-rule-provision-that-speeds-access-to-social-services/)

---

*Finding A Bed Tonight — Hospital Privacy Summary*
*AI-generated document. Review by your organization's privacy officer and legal counsel recommended.*
