# Theory of Change — Finding A Bed Tonight

> **AI-Generated Document:** This theory of change was produced by an AI assistant (Claude) based on the project's design, Harvard's IDEAS Impact Framework, and civic technology evaluation standards. The time reduction claim (55–140 minutes to 25–45 minutes) is qualified as a target outcome based on workflow analysis — it has not been independently measured. Review by the project team and pilot partners is recommended before use in grant applications.

## Problem

A family of five is sitting in a parking lot at midnight. A social worker has 30 minutes before the family's willingness to engage disappears. Currently, the worker makes serial phone calls to shelters that may be closed, full, or unable to serve that family's needs. There is no shared, real-time system for shelter bed availability in most US communities.

## Theory of Change

```
If outreach workers have real-time visibility into shelter bed availability
   across all shelters in their CoC...

Then the time from crisis contact to bed placement will decrease
   because workers can identify available beds immediately instead of
   making serial phone calls...

Which leads to fewer nights unsheltered for individuals and families
   in crisis, because the placement window (when a person is willing
   to accept services) is not lost to system friction.
```

## Target Outcome

**Reduce the time from crisis call to bed identification from an estimated 45-120 minutes to under 5 minutes.**

This is a target outcome for pilot measurement, not a verified claim. The estimate is based on workflow analysis:

| Step | Current (Phone-Based) | FABT |
|---|---|---|
| Identify which shelters serve this population | 5-15 min (worker's memory + calls) | 10 sec (filtered search) |
| Determine which have availability | 20-60 min (serial calls, voicemails, callbacks) | 5 sec (real-time data) |
| Confirm constraints match (pets, wheelchair, sobriety) | 5-15 min (questions per shelter) | Included in search filter |
| Reserve/hold a bed | 5-10 min (phone negotiation) | 3 taps (Hold This Bed) |
| **Total bed identification** | **35-100 min** | **< 5 min** |
| Transport and arrival | 20-40 min | 20-40 min (unchanged) |
| **Total crisis-to-placement** | **55-140 min** | **25-45 min** |

**Qualification:** These times are estimated based on workflow analysis, not measured in a controlled study. Pilot measurement with real outreach teams is required to validate.

## Measurable Indicators

| Indicator | Measurement Method | Data Source |
|---|---|---|
| Time to bed identification | Timestamp: search start to hold creation | FABT reservation table |
| Zero-result search rate | Searches returning 0 available beds / total searches | bed_search_log table |
| Reservation conversion rate | Confirmed arrivals / total holds | reservation table |
| Reservation expiry rate | Expired holds / total holds (proxy for failed placements) | reservation table |
| Beds identified per hour | Search volume × avg results | bed_search_log + analytics |
| Coordinator update frequency | Snapshots per shelter per day | bed_availability table |

All indicators are captured automatically by FABT's analytics module — no manual data collection required.

## Impact Chain

```
Activities                  → Outputs
─────────────────────────────────────────
Deploy FABT in a CoC        → Real-time bed availability visible to workers
Train outreach workers       → Workers use search instead of phone calls
Train coordinators           → Bed counts updated 2-4x daily

Outputs                     → Capacity Changes
─────────────────────────────────────────
Workers see live availability → Eliminated: serial phone calls, voicemails
Coordinators update counts   → System-wide visibility, not siloed knowledge
Analytics dashboard          → CoC admin sees demand patterns

Capacity Changes             → Behavior Changes
─────────────────────────────────────────
Faster bed identification    → Workers engage more families per shift
Reduced search friction      → Workers trust the system (if data is fresh)
Demand data visible          → CoC admin allocates resources to gaps

Behavior Changes             → Immediate Benefits
─────────────────────────────────────────
More placements per worker   → Fewer nights unsheltered
Faster holds                 → Placement window not lost
Data-driven planning         → Resources aligned to actual demand

Immediate Benefits           → Long-Term Change
─────────────────────────────────────────
Fewer nights unsheltered     → Reduced crisis escalation
Better resource allocation   → More efficient use of limited shelter capacity
Unmet demand data            → Evidence for funding requests and policy
```

## What We Can Prove Today

1. **The software works** — 597 automated tests, including bed search under concurrent analytics load (p99 152ms)
2. **The workflow is faster** — search → hold → confirm is 3 taps (vs. 5+ phone calls)
3. **Demand data is novel** — zero-result search tracking produces data no HMIS system currently captures
4. **DV privacy is protected** — zero PII, VAWA-aligned referral tokens, 24-hour hard delete

## What We Cannot Prove Yet

1. **The time reduction claim** — 55–140 min → 25–45 min is based on workflow analysis, not real-world measurement
2. **Worker adoption rate** — unknown until piloted with actual outreach teams
3. **Coordinator update consistency** — depends on shelter staff engagement
4. **Impact on unsheltered nights** — requires longitudinal data from a pilot

## Evidence Basis — "How Do You Know?"

The time reduction claim (55–140 minutes to 25–45 minutes) rests on three evidence types. None is sufficient alone; together they form a reasonable basis for a *target outcome*, not a verified measurement.

### 1. Baseline Estimate: 55–140 minutes (current process)

The baseline is a workflow analysis, not a published benchmark. HUD does not publish a "time-to-placement" metric — this is a known gap in System Performance Measures. The estimate is built from practitioner-reported steps:

| Step | Estimated Time | Basis |
|---|---|---|
| Identify shelters serving this population | 5–15 min | Worker tribal knowledge + resource lists |
| Determine availability (serial phone calls) | 20–60 min | 5–15 calls × 3–10 min each (hold times, voicemail, callbacks) |
| Confirm constraint match (pets, wheelchair, sobriety) | 5–15 min | Questions repeated per shelter |
| Negotiate a bed hold | 5–10 min | Verbal agreement with no guarantee |
| Transport to shelter | 20–40 min | Unchanged by technology |
| **Total** | **55–140 min** | |

**Limitations:** No published HUD, NAEH, or academic study rigorously measures this end-to-end time. Estimates are derived from practitioner descriptions in CoC applications, conference presentations, and published observations about outreach worker workflow (NAEH, Urban Institute). The range is intentionally wide to reflect this uncertainty.

### 2. FABT Digital Workflow: ~10 seconds (measured)

The FABT workflow from login to bed hold is measured by Playwright end-to-end tests running against a real backend with seeded data:

| Step | Actions | Measured Time |
|---|---|---|
| Login (tenant + credentials) | 4 taps | 1–2 sec |
| Search results load (automatic) | 0 taps | 1–3 sec |
| View shelter detail | 1 tap | 0.5–1.5 sec |
| Hold a bed | 1 tap | 1–2 sec |
| **Total digital workflow** | **6 taps** | **~5–9 sec** |

**Source:** `e2e/playwright/tests/outreach-search.spec.ts` — test timeouts reflect actual API response times against PostgreSQL with 10 shelters and 28 days of seeded activity data. Bed search API p99 is 95ms under concurrent load (Gatling simulation).

**Limitations:** Measured on a local dev stack, not a production deployment with real network latency. Does not include time for the outreach worker to converse with the client, assess needs, or make a placement decision — these are human-dependent and not reducible by technology.

### 3. Target Total: 25–45 minutes (projected)

| Step | FABT Time | Basis |
|---|---|---|
| Digital workflow (search + hold) | < 1 min | Measured (see above) |
| Client conversation + decision-making | 5–10 min | Estimate — not reducible by technology |
| Transport to shelter | 20–40 min | Unchanged — dependent on geography |
| **Total** | **25–45 min** | Projected, not measured end-to-end |

**What FABT eliminates:** Serial phone calls (20–60 min), constraint re-verification (5–15 min), verbal hold negotiation (5–10 min). Total eliminated: 30–85 minutes of system friction.

**What FABT does not change:** Client engagement time, transport time, shelter intake process. These are the floor — no technology can reduce them below ~25 minutes.

### How to validate

FABT already captures the data needed for real-world measurement:

| Metric | Source | Available Today |
|---|---|---|
| Time from search to hold | `reservation.created_at - bed_search_log.searched_at` | Yes (requires matching by user/session) |
| Hold-to-confirm duration | `reservation.confirmed_at - reservation.created_at` | Yes |
| Zero-result search rate | `bed_search_log` where results = 0 | Yes |
| Searches per placement | Count of searches per user per confirmed reservation | Yes |

A 90-day pilot with 5+ shelters and 3+ outreach workers would produce sufficient data to validate or revise the time reduction claim. The `bed_search_log` and `reservation` tables are designed for this purpose.

## Recommended Language for Grant Applications

> "Finding A Bed Tonight is designed to reduce total crisis-to-placement time from an estimated 55–140 minutes (serial phone calls, stale spreadsheets) to 25–45 minutes by providing real-time bed availability with a 3-tap hold to secure the bed during transport. This target is based on workflow analysis; the digital workflow alone (search to hold) takes under 10 seconds in end-to-end testing. Pilot measurement with [named partner] is planned for [timeframe]."

This language is:
- **Honest** — "designed to reduce" and "target" rather than "reduces"
- **Specific** — names the workflow basis for the claim
- **Actionable** — commits to measurement via a named pilot
- **Credible** — a funder can evaluate the workflow logic independently

---

*Finding A Bed Tonight — Theory of Change*
*AI-generated document. The time reduction claim (55–140 min → 25–45 min) is a target outcome based on workflow analysis, not a verified measurement.*
*Review by project team and pilot partners recommended before use in grant applications.*
