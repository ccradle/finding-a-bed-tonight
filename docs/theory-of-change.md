# Theory of Change — Finding A Bed Tonight

> **AI-Generated Document:** This theory of change was produced by an AI assistant (Claude) based on the project's design, Harvard's IDEAS Impact Framework, and civic technology evaluation standards. The "2 hours to 20 minutes" claim is qualified as a target outcome — it has not been independently measured. Review by the project team and pilot partners is recommended before use in grant applications.

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

1. **The software works** — 431 automated tests, including bed search under concurrent analytics load (p99 152ms)
2. **The workflow is faster** — search → hold → confirm is 3 taps (vs. 5+ phone calls)
3. **Demand data is novel** — zero-result search tracking produces data no HMIS system currently captures
4. **DV privacy is protected** — zero PII, VAWA-aligned referral tokens, 24-hour hard delete

## What We Cannot Prove Yet

1. **The "2 hours to 20 minutes" claim** — unmeasured in real-world use
2. **Worker adoption rate** — unknown until piloted with actual outreach teams
3. **Coordinator update consistency** — depends on shelter staff engagement
4. **Impact on unsheltered nights** — requires longitudinal data from a pilot

## Recommended Language for Grant Applications

> "Finding A Bed Tonight is designed to reduce the time from crisis contact to bed identification from an estimated 45-120 minutes (serial phone calls) to under 5 minutes (real-time search and 3-tap hold). This target is based on workflow analysis; pilot measurement with [named partner] is planned for [timeframe]."

This language is:
- **Honest** — "designed to reduce" and "target" rather than "reduces"
- **Specific** — names the workflow basis for the claim
- **Actionable** — commits to measurement via a named pilot
- **Credible** — a funder can evaluate the workflow logic independently

---

*Finding A Bed Tonight — Theory of Change*
*AI-generated document. The time reduction claim is a target outcome, not a verified measurement.*
*Review by project team and pilot partners recommended before use in grant applications.*
