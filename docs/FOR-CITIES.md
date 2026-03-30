# For City and County Officials

> This page is written for city housing officials, procurement officers, and city attorneys evaluating this platform for municipal adoption. For technical details, see [FOR-DEVELOPERS.md](FOR-DEVELOPERS.md).
>
> **Note on personas:** Teresa Nguyen is an AI-generated persona defined in [PERSONAS.md](../PERSONAS.md) to guide design decisions. She represents the type of city official this platform is built for, not a real individual.

---

## What Is This?

Finding A Bed Tonight is free, open-source software that provides real-time emergency shelter bed availability for a community. Outreach workers see which shelters have open beds, filter by need (families, individuals, wheelchair access, pets), and place a temporary hold on a bed while transporting someone to the shelter.

It replaces the current process in most communities: serial phone calls to shelters that may be closed, full, or unable to serve the person's needs.

---

## Who Owns the Data?

**You do.** This is self-hosted software. Your data lives on infrastructure you control -- your servers, your cloud account, your jurisdiction. No data is transmitted to the project maintainers or any third party unless you configure it to do so (e.g., HMIS push to your chosen vendor).

The Apache 2.0 open-source license means:
- You can run the software indefinitely, for free
- You can modify the source code to meet your needs
- You can hire any contractor to maintain or extend it
- There is no vendor lock-in, no per-seat licensing, no subscription fee
- The license is irrevocable -- it cannot be changed retroactively

---

## Accessibility (WCAG 2.1 AA)

The platform has undergone a self-assessed accessibility review targeting WCAG 2.1 Level AA conformance. The self-assessment is documented in an Accessibility Conformance Report (ACR) using the VPAT 2.5 WCAG edition format.

**What this means:** The development team has evaluated the platform against WCAG 2.1 AA criteria and documented conformance and known gaps. This is a self-assessment, not an independent third-party audit. A third-party audit is a planned next step and would be enabled by additional funding.

See [WCAG-ACR.md](WCAG-ACR.md) for the full self-assessed conformance report.

---

## Security Posture

The platform implements defense-in-depth security practices:

- **OWASP Dependency Check** -- the CI pipeline fails the build if any dependency has a known vulnerability with CVSS score 7 or higher
- **Row Level Security (RLS)** -- PostgreSQL enforces tenant isolation and DV shelter access at the database layer, not just the application layer
- **Rate limiting** -- API endpoints are rate-limited to prevent abuse
- **ZAP scan baseline** -- OWASP ZAP automated security scanning is used as part of the security review process
- **Restricted database role** -- the application connects as a non-superuser role (`fabt_app`) with DML-only permissions
- **CSV injection prevention (CWE-1236)** -- all imported data is sanitized to prevent formula injection when exported to spreadsheets
- **JWT authentication** -- stateless authentication with configurable secret strength
- **Multi-tenant isolation** -- each CoC's data is isolated by tenant at every layer

**What this means:** The project follows industry-standard security practices and maintains automated security gates in its build pipeline. It has not undergone a formal third-party penetration test. The security posture is designed to support municipal security requirements, not certified against a specific compliance framework.

For details, see the [security documentation](security/).

---

## Apache 2.0 and City Procurement

The Apache 2.0 license is one of the most permissive and well-understood open-source licenses. Key points for your procurement process:

| Question | Answer |
|---|---|
| Can the city use this software? | Yes. Apache 2.0 permits use by any organization, including government. |
| Can we modify it? | Yes. You can modify the source code for any purpose. |
| Do we have to share our modifications? | No. Apache 2.0 does not require you to publish changes (unlike GPL). |
| Is there patent risk? | Apache 2.0 includes an explicit patent grant from contributors. |
| Who is liable? | The software is provided "as is" with no warranty, consistent with standard open-source terms. Your liability posture is comparable to other open-source software your city already uses (PostgreSQL, Linux, etc.). |
| Can the license change? | Not retroactively. Code released under Apache 2.0 remains under Apache 2.0 forever. |

Many government agencies already use Apache 2.0 software (Apache HTTP Server, Kubernetes, Android). The license is well-tested in government procurement contexts.

For a comprehensive procurement and adoption guide, see the [Government Adoption Guide](government-adoption-guide.md).

---

## What Happens If You Stop Using It?

Your data remains yours. The platform supports data export in HSDS 3.0 (Human Services Data Specification) format, which is the open standard for human services data. You can export your shelter data, availability history, and analytics at any time.

If you stop using the platform:
- Your existing data exports are in a standard, portable format
- Your server continues to run until you shut it down
- There is no contract to terminate, no cancellation fee, no data hostage scenario

---

## Support Model

This is a community-supported open-source project. Current support is best-effort via documentation and GitHub Issues.

For municipalities that require formal support arrangements, the platform is designed so that any qualified IT professional or managed services provider can maintain a deployment. The architecture is documented, the deployment is containerized, and the codebase uses mainstream technologies (Java, Spring Boot, PostgreSQL, React).

See [Support Model](support-model.md) for a detailed description of support tiers and options.

---

## How Is This Different from Commercial Alternatives?

| | Finding A Bed Tonight | Commercial Platforms |
|---|---|---|
| **Cost** | Free software + hosting ($15-100+/mo) | Per-seat licensing ($5,000-50,000+/yr) |
| **Data ownership** | You own it, on your infrastructure | Vendor-hosted, vendor-controlled |
| **Vendor lock-in** | None. Apache 2.0, standard formats. | Proprietary formats, contract terms |
| **Customization** | Full source code access | Feature requests to vendor |
| **DV privacy** | Zero-PII, zero-address, 24-hour hard delete | Varies by vendor |
| **Transparency** | Open source, auditable | Closed source |
| **Support** | Community + self-hosted | Vendor support (included in license fee) |

The tradeoff is honest: commercial platforms include dedicated vendor support. This project relies on community support and your own IT capacity. For many communities, the cost savings and data sovereignty outweigh that tradeoff. For others, a commercial platform may be the right choice.

---

## Government Adoption Guide

A comprehensive guide covering procurement, deployment, security, accessibility, and ongoing operations is available at [Government Adoption Guide](government-adoption-guide.md). It is designed to answer the questions your city attorney and IT security team will ask.

---

## Summary for Decision-Makers

| Question | Answer |
|---|---|
| Who owns the data? | You do. Self-hosted, your infrastructure. |
| What does it cost? | $15-100+/month for hosting. Software is free. |
| Is it accessible? | Self-assessed WCAG 2.1 AA. Third-party audit planned. |
| Is it secure? | OWASP gate, RLS, rate limiting, CSV injection prevention, ZAP baseline. No formal pen test yet. |
| What if we stop using it? | Export in HSDS 3.0 format. No lock-in. |
| Who supports it? | Community support. Any IT professional can maintain it. |
| What is the license? | Apache 2.0 -- permissive, irrevocable, government-friendly. |

---

*Finding A Bed Tonight -- For City and County Officials*
