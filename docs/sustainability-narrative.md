# Sustainability Narrative — Finding A Bed Tonight

> **AI-Generated Document:** This narrative was produced by an AI assistant (Claude) based on published sustainability models from Open Source Collective, Software Freedom Conservancy, Code for America, and CKAN. Review by the project team and advisors (particularly Priya Anand) is recommended before use in funding conversations.

## The Question

**"What keeps this project alive in year 3?"**

This is the question every foundation program officer asks before recommending a grant. It's a fair question, and it deserves an honest answer.

## Current State

FABT is currently maintained by a small project team. The software is stable (v0.38.0, 619 automated tests), the architecture is documented, and the Apache 2.0 license ensures permanent availability. But a single maintainer is not a sustainability model — it's a starting point.

## Sustainability Path

### Stage 1: Now — Open Source Community (Current)

- Apache 2.0 licensed, source code permanently available on GitHub
- Community support via GitHub Issues and documentation
- Zero vendor dependency — any IT professional can maintain a deployment
- Cost to operate: $15-30/month for Lite tier hosting

**What this provides:** Permanence. The software cannot disappear. Even if development pauses, every existing deployment continues to run, and any organization can maintain their own instance.

### Stage 2: With Pilot Adoption — Fiscal Sponsor

When the first pilot deployment is active, establish a fiscal home:

**Option A: Open Source Collective** (501c6)
- 10% fee on contributions
- No setup fee
- Financial administration, tax compliance, legal entity
- Donations are not tax-deductible
- Used by nearly 3,000 open source communities

**Option B: Software Freedom Conservancy** (501c3)
- Negotiated percentage of revenue
- Donations ARE tax-deductible
- Legal services, conference support, financial management
- Hosts Git, Selenium, PostgreSQL (via SPI)
- More selective — requires demonstrated community

**Option C: A local NC nonprofit** as fiscal sponsor
- Would leverage proximity to Wake County CoC and Raleigh housing organizations (no existing relationship — would need to be established)
- Keeps governance local and accountable to the community being served

**What this provides:** A financial entity that can receive foundation grants, CoC contributions, and government contracts. FABT becomes fundable.

### Stage 3: With Multiple Adopters — Shared Maintenance

When 3+ CoCs adopt FABT, establish a cost-sharing consortium:

- Each participating CoC contributes a proportional share of maintenance costs
- A shared IT resource handles updates, security patches, and infrastructure
- Governance: an advisory board with representation from each adopting CoC

**Precedent:** Open311 cities bootstrap each other — Bloomington, IN's CRM was redeployed in Columbus, IN and Peoria, IL with minimal adaptation. CKAN operates with two co-stewards and an ecosystem of community contributors.

**What this provides:** Distributed risk. No single organization bears the full maintenance burden.

### Stage 4: At Scale — Institutional Host

At 10+ adopters, one of several paths:

- **Code for America** (or a local civic tech organization) adopts FABT as a maintained project
- **A university** (e.g., NC State, Duke) hosts the project as a public interest technology initiative
- **HUD or a CoC** contracts for ongoing maintenance as part of HMIS infrastructure funding
- **A commercial entity** offers managed hosting + support (the Red Hat model for open source)

**What this provides:** Long-term institutional backing with dedicated staff.

## Funding Opportunities

| Funder | Focus | Fit for FABT |
|---|---|---|
| Knight Foundation | Civic tech, information access | Strong — real-time public information system |
| Mozilla Foundation | Open-source public interest technology | Strong — Apache 2.0, zero-PII, community benefit |
| MacArthur Foundation | Safety and justice, housing | Moderate — housing focus, DV protection angle |
| NC Community Foundation | Local NC initiatives | Strong — NC-based project, would need local CoC partnership |
| HUD Technology Grants | CoC technology, HMIS integration | Strong — if a CoC is formally involved |
| Local corporate foundations | CRA obligations (banks, healthcare) | Moderate — community investment angle |

## What to Tell a Funder

> "FABT is currently maintained by the project team under Apache 2.0. The sustainability plan has three stages: (1) establish a fiscal sponsor to receive grants and contributions, (2) create a cost-sharing consortium as additional CoCs adopt, and (3) seek institutional hosting at scale. The software's zero-vendor-dependency architecture ensures that every deployment survives independently regardless of project-level governance changes."

## What to Tell a City Official

> "This is open-source software — like PostgreSQL, which powers many city databases. Your deployment runs on your infrastructure. If the project team disappeared tomorrow, your system continues to run unchanged. Updates and new features come from the community, and the Apache 2.0 license ensures you can always modify or maintain the software yourself or through a contractor."

## Measurement posture — notification deep-linking (2026-05-01)

The notification-deep-linking change (shipped as v0.39.0, 2026-04-14) added
three Micrometer instruments — a deep-link click counter, a stale-referral
counter, and a time-to-action histogram — with three Grafana panels on the
DV Referrals dashboard. The intent (Priya Anand's lens) was to enable a
pre/post comparison of coordinator time-from-notification-to-referral-accept
once a pilot deployment was active.

**Operational signal verified live (findabed.org, 2026-05-01):**

| Instrument | 30-day max state | Read |
|---|---|---|
| `fabt_notification_deeplink_click_count_total` | 0 series — never incremented | No bell-click telemetry recorded on the demo site |
| `fabt_notification_stale_referral_count_total` | 0 series — never incremented | No stale-fallback incidents recorded |
| `fabt_notification_time_to_action_seconds_*` | 4 observations across 3 types (1 escalation.1h + 1 escalation.3_5h + 2 referral.requested) | Histogram populated, almost certainly by CI / Playwright traffic; quantiles saturated at the 30s bucket boundary |

**What this means for the measurement narrative:**

The metrics infrastructure is verified live and shape-correct. Prometheus
scrapes the backend cleanly; the histogram registers the expected 4 series
(bucket / count / max / sum); the Grafana panels render. A pilot
deployment with active coordinator users will materialize the pre/post
comparison Priya asked for. The demo site itself does not exercise the
bell-click flow because demo traffic uses Try-It-Live preset users walking
prepared paths — the bell-click code path is exercised only by tests.

This is the honest reading per `feedback_truthfulness_above_all`: we have
operational signal verified, but no pilot baseline. Pre/post measurement
becomes possible the day a pilot CoC's coordinators start clicking
notifications in normal workflow.

---

*Finding A Bed Tonight — Sustainability Narrative*
*AI-generated document. Review by project team and funding advisors recommended.*
