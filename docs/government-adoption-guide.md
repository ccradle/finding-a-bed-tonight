# Government Adoption Guide — Finding A Bed Tonight

> **AI-Generated Document:** This guide was produced by an AI assistant (Claude) based on published open-source procurement guidance, Apache 2.0 license terms, and civic technology adoption precedents. It should be reviewed by qualified legal counsel before use in any procurement or adoption process. This document does not constitute legal advice.

## Audience

City attorneys, CoC board members, and government procurement officers evaluating FABT for adoption.

## What Is This Software?

Finding A Bed Tonight (FABT) is an open-source emergency shelter bed availability platform licensed under **Apache License 2.0**. It matches individuals and families experiencing homelessness to available shelter beds in real time. It is designed to be self-hosted by the adopting organization.

## Data Ownership

**You own your data. Completely.**

In a self-hosted deployment, all data resides on infrastructure you control. There is no SaaS provider, no cloud vendor, and no third party with access to your data. Specifically:

- The PostgreSQL database runs on your server
- All shelter, user, availability, and analytics data is stored locally
- No data is transmitted to the FABT project, its maintainers, or any external service
- HMIS push is configured by you and pushes only to vendors you authorize
- There is no telemetry, analytics, or usage tracking sent externally

**This is identical to how governments host PostgreSQL, CKAN, or any other self-hosted open-source software.** Your IT department or hosting provider is the sole custodian of the data.

## Software License — Apache 2.0

Apache License 2.0 is one of the most widely adopted open-source licenses in government. It is used by the Android operating system, Apache HTTP Server (which powers a significant percentage of federal websites), and thousands of government-deployed tools.

**What Apache 2.0 permits:**
- Free use for any purpose, including commercial and government use
- Modification and redistribution without restriction
- No per-seat licensing, no annual renewal fees, no usage limits
- Patent grant from contributors (protection against patent claims)

**What Apache 2.0 does NOT provide:**
- Warranty of any kind (software is provided "AS IS")
- Indemnification from the project or its contributors
- Guaranteed support or maintenance obligations

**Precedent:** CKAN (data.gov), Open311 (40+ cities), Linux (Department of Defense), PostgreSQL (IRS, NOAA, State Department) all use permissive open-source licenses in government deployments.

## Procurement Path

### Is open-source software procurable?

Yes. The 18F (GSA) Open Source Policy explicitly encourages federal agencies to use and contribute to open-source software. The Department of Defense requires an "Adopt, Buy, Create" approach that preferentially adopts existing open-source solutions before purchasing proprietary alternatives.

### How to procure FABT

FABT is free to download and deploy. No procurement of the software itself is required. What may be procured:

1. **Hosting infrastructure** — A cloud VM or on-premises server running PostgreSQL and the FABT application ($15-30/month for Lite tier)
2. **Deployment services** — An IT services firm to perform initial setup, configuration, and integration
3. **Support contract** — A systems integrator to provide ongoing monitoring, updates, and on-call support (if SLA required)

The Apache 2.0 license explicitly permits commercial support services. This is how PostgreSQL, Linux, and CKAN are supported in government deployments.

### What RFP language to use

> "The City seeks a shelter bed availability platform. Open-source solutions licensed under OSI-approved licenses (Apache 2.0, MIT, GPL) are acceptable. The selected solution must be self-hostable, provide WCAG 2.1 AA conformance, and support HUD HIC/PIT data export."

## Security Posture

- **OWASP:** CI pipeline includes dependency vulnerability scanning (failBuildOnCVSS=7)
- **RLS:** PostgreSQL Row Level Security protects DV shelter data at the database layer
- **Authentication:** JWT + OAuth2/OIDC + API key hybrid authentication
- **Multi-Factor Authentication:** TOTP-based sign-in verification (Google Authenticator, Authy) designed to support NIST 800-63B AAL2 and CJIS Security Policy MFA requirements (effective Oct 2024 for organizational users). TOTP shared secrets encrypted at rest with AES-256-GCM (key stored outside the database). 8 single-use backup codes per user (bcrypt-hashed). mfaToken is single-use with 5-attempt rate limiting. SMS-based OTP intentionally excluded due to SIM swap risk with DV survivor data. This implementation has not been independently certified as CJIS compliant.
- **SSO Resilience:** If an identity provider (Google, Microsoft, Keycloak) experiences an outage, password-authenticated users continue working without interruption. The two authentication paths are architecturally independent. See docs/runbook.md for full degradation behavior.
- **Credential Management:** Self-service password change, admin-initiated password reset, and admin-generated one-time access codes for field worker recovery (15-minute expiry, single-use, bcrypt-hashed). Minimum 12-character passwords per NIST 800-63B (length over complexity). All existing sessions are invalidated on password change. SSO-only users are handled gracefully. DV safeguard: access code generation for DV-authorized users requires DV-authorized admin.
- **Rate Limiting:** Authentication and password management endpoints are protected against brute force attacks (login: 10/15min, password change: 5/15min, admin reset: 10/15min, TOTP verification: 20/15min + 5 per token, forgot-password: 3/60min per IP)
- **DV Protection:** Defense-in-depth — database RLS + service-layer access checks + zero client PII
- **Audit:** HMIS push audit log with SHA-256 payload hashing
- **WCAG:** Self-assessed ACR covering all 50 WCAG 2.1 Level A/AA criteria (see docs/WCAG-ACR.md). Typography system uses CSS custom properties as centralized design tokens — consistent font rendering across all platforms, automated Playwright tests verify WCAG 1.4.12 text spacing compliance.
- **Security Scanning:** OWASP ZAP API scan run against a local development environment (HTTP, no TLS) using the OpenAPI spec — zero HIGH/CRITICAL findings across 116 checks. This covers application-level vulnerabilities (injection, XSS, access control). TLS configuration, reverse proxy hardening, and production infrastructure scanning have not yet been performed and should be completed before any public-facing deployment. See docs/security/ for the baseline report.

For a detailed security posture summary, see the code repository's README and the operational runbook (docs/runbook.md).

## Support Model

**Current reality (honest):**

FABT is maintained by the project team on a best-effort basis. Support is provided through:
- **GitHub Issues** for bug reports and feature requests
- **Documentation** including a comprehensive operational runbook (docs/runbook.md)
- **Source code** — fully available for review, modification, and debugging

This is the standard community support model used by PostgreSQL, Metabase, Signal, and thousands of production-grade open-source projects.

**For organizations requiring an SLA:**

Cities or CoCs that require guaranteed response times can contract with a systems integrator or IT services firm to provide deployment, monitoring, and on-call support. The Apache 2.0 license explicitly permits this. This is how PostgreSQL, Linux, and CKAN are supported in government deployments where formal SLAs are required.

## What Happens If the City Exits the Platform?

**Your data stays with you.**

1. Export all data via standard PostgreSQL tools (`pg_dump`)
2. Export shelter inventory in HIC CSV format from the Admin UI
3. Export PIT count data in HUD format from the Admin UI
4. Decommission the application and database server

There is no vendor lock-in, no proprietary data format, and no exit penalty. The database schema is fully documented in DBML format (docs/schema.dbml).

## What Happens If the Project Is Abandoned?

The source code is permanently available under Apache 2.0 on GitHub. Even if the project team stops maintaining it:

- Your deployment continues to run unchanged
- You retain full rights to modify the code
- Any IT team can maintain, patch, and extend the software
- The Apache 2.0 license is irrevocable — it cannot be withdrawn

This is the same assurance that applies to PostgreSQL, Linux, or any Apache-licensed software.

## DV Shelter Liability — VAWA Protection

FABT is designed to support VAWA (34 U.S.C. 12291(b)(2)), FVPSA, and HUD HMIS confidentiality requirements. Key protections:

- **Zero client PII** — no client names, SSNs, DOBs, or addresses in the database
- **Opaque referral** — DV referral tokens contain only household size, urgency, and worker callback number
- **24-hour hard delete** — terminal referral tokens are permanently deleted within 24 hours
- **Court subpoena response** — nothing survives to be subpoenaed because data is hard-deleted, not soft-deleted
- **Address redaction** — DV shelter addresses are never exposed in API responses to unauthorized users
- **Small-cell suppression** — DV aggregate data is suppressed when fewer than 3 DV shelters exist (Design D18)

See docs/DV-OPAQUE-REFERRAL.md for the complete legal basis, architecture, and VAWA compliance checklist.

**Important:** This design is intended to support compliance but does not constitute legal compliance certification. Organizations deploying FABT for DV referrals should consult qualified legal counsel regarding applicable federal, state, and local confidentiality requirements.

## Frequently Asked Questions

**Q: Can multiple CoCs share one deployment?**
A: Yes. FABT is multi-tenant — each CoC operates as an isolated tenant with its own shelters, users, and configuration. Data is separated by tenant_id at the database level with Row Level Security enforcement.

**Q: Is there a staging/test environment?**
A: The dev-start.sh script creates a complete local environment with seed data for training and testing. Production and staging environments are standard DevOps — deploy to separate infrastructure with separate databases.

**Q: How do software updates get deployed?**
A: Updates are released as tagged versions on GitHub. Deployment is a standard JAR replacement + Flyway migration (automated). The operational runbook documents the process.

**Q: How is this different from San Diego's Shelter Ready?**
A: FABT is open-source (Apache 2.0), self-hosted, and designed for any CoC to adopt independently. Shelter Ready is a city-funded application specific to San Diego. FABT's multi-tenant architecture, DV privacy protections, and HUD export tools are designed for broader CoC adoption.

---

*Finding A Bed Tonight — Government Adoption Guide*
*AI-generated document. Review by qualified legal counsel recommended before use in procurement.*
