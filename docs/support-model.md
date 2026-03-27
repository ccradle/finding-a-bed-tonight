# Support Model — Finding A Bed Tonight

> **AI-Generated Document:** This description was produced by an AI assistant (Claude) based on open-source support models from PostgreSQL, Metabase, Signal, and CKAN. Review by the project team is recommended.

## The Honest Answer to "Who Do You Call at 2am?"

### Current Reality

FABT is an open-source project maintained by the project team on a best-effort basis. This is the same support model used by PostgreSQL, Signal, Metabase (open source edition), and thousands of production-grade open-source tools in government and enterprise use.

**What this means practically:**

| Scenario | What Happens |
|---|---|
| Bug found during business hours | File a GitHub Issue. Response typically within 1-3 business days. |
| Something breaks at 2am | Consult the operational runbook (docs/runbook.md). The runbook covers common failure scenarios with investigation and resolution steps. File a GitHub Issue if the runbook doesn't cover it. |
| SSO provider goes down | Password-authenticated users continue working without interruption — the two authentication paths are architecturally independent. SSO users are locked out until the provider recovers. See runbook "IdP outage" section. Recommendation: maintain at least one password-based admin account per tenant as a fallback. |
| Feature request | File a GitHub Issue with the feature description. Community discussion determines priority. |
| Security vulnerability | Report privately via GitHub Security Advisories. Treated as highest priority. |
| Need help with deployment | Documentation covers step-by-step deployment. GitHub Discussions for questions. |

### What You Get Without Paying Anything

- **Operational runbook** (docs/runbook.md) — covers health checks, common issues, database operations, DV referral operations, HMIS bridge operations, batch job monitoring
- **GitHub Issues** — bug tracking with community response
- **Source code** — full transparency, ability to debug and fix issues yourself
- **Documentation** — architecture, API docs (Swagger), schema (DBML), deployment guide

### What You Do NOT Get

- Guaranteed response times (no SLA)
- 24/7 phone support
- Dedicated account manager
- Guaranteed fix timelines for non-critical issues

### For Organizations That Require an SLA

Cities, counties, or CoCs that require contractual service level agreements have two paths:

**Path 1: Contract with a systems integrator**

Engage a local IT services firm or managed services provider to:
- Deploy and maintain the FABT instance
- Provide 24/7 monitoring and alerting
- Respond to incidents per an agreed SLA
- Apply updates and security patches

The Apache 2.0 license explicitly permits commercial support services. This is how PostgreSQL, Linux, and CKAN are supported in government deployments.

**Path 2: City IT department maintenance**

Include FABT in the city's existing IT infrastructure portfolio:
- Treat it like any other self-hosted application (alongside email, CRM, website)
- City IT staff handle deployment, monitoring, and updates
- Leverage existing on-call rotation for infrastructure incidents

### How This Compares

| Project | Free Support | Paid Support Available? |
|---|---|---|
| **FABT** | GitHub Issues, runbook, docs | Via third-party IT services (Apache 2.0 permits) |
| **PostgreSQL** | Mailing lists, community | Yes — EDB, Crunchy Data, AWS RDS |
| **Metabase** | Forum only | Yes — Metabase Cloud with email SLA |
| **CKAN** | GitHub, community | Yes — Datopian, Link Digital |
| **Signal** | Support Center, community forum | No — donation-funded, no commercial tier |
| **Linux** | Community forums | Yes — Red Hat, Canonical, SUSE |

### The Path Forward

The support model will evolve with adoption:

1. **Now:** Community support (GitHub Issues, documentation, runbook)
2. **With pilot adoption:** Additional documentation and FAQ based on real-world deployment experience
3. **With multiple adopters:** Potential for shared maintenance consortium or fiscal sponsor model
4. **At scale:** Potential for a nonprofit host or commercial support provider to emerge

This is how every successful open-source project evolves. PostgreSQL started as a university research project. CKAN started as a single organization's tool. The support ecosystem grows with adoption.

---

*Finding A Bed Tonight — Support Model*
*AI-generated document. Review by project team recommended.*
