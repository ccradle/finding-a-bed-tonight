# QA Briefing: Bed Availability Calculation Correctness

**Author:** QA Engineering (Riley Cho)
**Date:** March 2026
**Severity:** P0 — Core Platform Correctness
**Audience:** QA and Development team
**Repo:** `ccradle/finding-a-bed-tonight`

---

## Why This Document Exists

We have identified a correctness issue in the core calculation that determines
how many beds are available at a shelter. This is the most important number in
the entire platform. An outreach worker at midnight trusts this number to decide
whether to drive across Raleigh with a family in their car.

If that number is wrong, a family arrives at a shelter that is full.
If that happens more than once, the outreach worker stops trusting the platform.
If outreach workers stop trusting the platform, the platform has failed — not
because it went down, but because it lied quietly and nobody noticed until the
damage was done.

**The goal of this work is not to make tests pass.**

The goal is to find every situation in which the availability calculation produces
a wrong answer, fix the underlying logic, and then write tests that will catch
any future regression in that logic. Tests that pass over broken logic are worse
than no tests — they create false confidence.

Start by assuming the calculation is wrong. Go looking for the bugs. Fix them.
Then write tests that verify the fixed behavior and will fail loudly if the logic
ever breaks again.

---

## The Calculation

Available beds is derived — never stored. The formula is:

```
beds_available = beds_total - beds_occupied - beds_on_hold
```

All three inputs come from the most recent `bed_availability` snapshot for a
given `(shelter_id, population_type)` combination. The snapshot is append-only —
new rows are inserted, existing rows are never updated or deleted. The query
reads `DISTINCT ON (shelter_id, population_type) ORDER BY snapshot_ts DESC`.

**Three ways the number changes:**

1. A coordinator submits an availability update (PATCH `/api/v1/shelters/{id}/availability`)
   — sets `beds_occupied` and/or `beds_on_hold` and/or `beds_total` directly
2. An outreach worker places a reservation hold — increments `beds_on_hold` by 1,
   decrements it back on confirm/cancel/expire
3. A reservation is confirmed — decrements `beds_on_hold` by 1, increments
   `beds_occupied` by 1

Each of these creates a new snapshot row. The available count is derived from
the latest snapshot. If the latest snapshot is wrong, the displayed number
is wrong.

---

## Known Symptom

The UI permits coordinators to:
- Increase or decrease `beds_occupied`
- Increase or decrease `beds_total`

Under some sequences of operations, the computed `beds_available` does not
reflect the actual state. The specific failure modes are not yet fully
characterized — that is the purpose of this investigation.

---

## The Test Philosophy for This Investigation

> **Find the bug first. Write the test second. The test proves the fix is real.**

Do not start by writing tests that pass against the current implementation.
Start by writing tests that expose the incorrect behavior. A test that fails
against the current code and passes after the fix is a meaningful test.
A test written after the fact to pass against unfixed logic is just noise.

For each scenario below, the first question is:
**"Does the system currently produce the correct answer here?"**

If the answer is no, document what it produces and what it should produce.
Fix the logic. Then formalize the test.

---

## Invariants — Rules That Must Never Be Violated

Before listing test cases, establish what must always be true. These are the
invariants. Any state that violates an invariant is a bug, regardless of how
it was reached.

**INV-1: beds_available must never be negative**
```
beds_total - beds_occupied - beds_on_hold >= 0
```
A negative available count means more beds are claimed than exist.
This is a data corruption condition, not just a display error.

**INV-2: beds_occupied must never exceed beds_total**
```
beds_occupied <= beds_total
```

**INV-3: beds_on_hold must never exceed (beds_total - beds_occupied)**
```
beds_on_hold <= (beds_total - beds_occupied)
```
You cannot hold a bed that is already occupied.

**INV-4: beds_total must never be negative**
```
beds_total >= 0
```

**INV-5: The sum of claimed beds must never exceed total**
```
beds_occupied + beds_on_hold <= beds_total
```

**INV-6: A confirmation must not increase beds_available**
When a reservation is confirmed (HELD → CONFIRMED), a held bed becomes
an occupied bed. `beds_on_hold` decrements by 1, `beds_occupied` increments
by 1. `beds_available` must not change.

**INV-7: A cancellation must increase beds_available by exactly 1**
When a reservation is cancelled or expires, the held bed is released.
`beds_on_hold` decrements by 1. `beds_available` increments by exactly 1.

**INV-8: A new hold must decrease beds_available by exactly 1**
When a hold is placed, `beds_on_hold` increments by 1.
`beds_available` decrements by exactly 1.

**INV-9: beds_available after any operation must equal the formula**
At any point in time, for any shelter and population type:
`beds_available == beds_total - beds_occupied - beds_on_hold`
This must hold immediately after every write operation — not eventually,
not after a cache flush, immediately.

---

## Test Cases

The following are organized from simple to complex. Do not skip the simple ones
— the invariant violations in complex scenarios often trace back to a failure
in a simple one.

---

### GROUP 1: Baseline Coordinator Updates

These are the simplest cases. A single coordinator updates a single shelter.
No reservations, no concurrent operations.

**TC-1.1: Initial snapshot — no prior state**
```
Setup:   New shelter, no prior availability snapshot
Action:  PATCH availability with beds_total=10, beds_occupied=3, beds_on_hold=0
Expect:  beds_available = 7
         Invariants INV-1 through INV-5 satisfied
```

**TC-1.2: Increase occupied beds**
```
Setup:   beds_total=10, beds_occupied=3, beds_on_hold=0 → beds_available=7
Action:  PATCH with beds_total=10, beds_occupied=5, beds_on_hold=0
Expect:  beds_available = 5 (decreased by 2)
         beds_available did not go below 0
```

**TC-1.3: Decrease occupied beds (guests leave)**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=0 → beds_available=2
Action:  PATCH with beds_total=10, beds_occupied=4, beds_on_hold=0
Expect:  beds_available = 6 (increased by 4)
```

**TC-1.4: Increase total beds (capacity expansion)**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=0 → beds_available=2
Action:  PATCH with beds_total=15, beds_occupied=8, beds_on_hold=0
Expect:  beds_available = 7 (increased by 5)
```

**TC-1.5: Decrease total beds (capacity reduction)**
```
Setup:   beds_total=10, beds_occupied=3, beds_on_hold=0 → beds_available=7
Action:  PATCH with beds_total=7, beds_occupied=3, beds_on_hold=0
Expect:  beds_available = 4
```

**TC-1.6: Decrease total beds below current occupied — the dangerous case**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=0 → beds_available=2
Action:  PATCH with beds_total=5, beds_occupied=8, beds_on_hold=0
Question: Should this be accepted? If accepted, what is beds_available?
         beds_available = 5 - 8 - 0 = -3 → VIOLATES INV-1

Expected behavior:
  EITHER the API rejects this update with 422 (validation error), explaining
  that beds_total cannot be set below beds_occupied
  OR the API accepts it and clamps beds_available to 0 (with a warning)
  
  The current behavior must be characterized. If it silently produces -3,
  that is a bug. Document which behavior is chosen and enforce it in tests.
```

**TC-1.7: Decrease total beds below occupied + on_hold**
```
Setup:   beds_total=10, beds_occupied=5, beds_on_hold=3 → beds_available=2
Action:  PATCH with beds_total=6, beds_occupied=5, beds_on_hold=3
Expected: 6 - 5 - 3 = -2 → VIOLATES INV-1

Same question as TC-1.6. What does the system do?
Characterize it. Fix it if it violates the invariant silently.
```

**TC-1.8: Set all fields to zero**
```
Setup:   beds_total=10, beds_occupied=3, beds_on_hold=0 → beds_available=7
Action:  PATCH with beds_total=0, beds_occupied=0, beds_on_hold=0
Expect:  beds_available = 0
         No negative values
         Shelter shows as full/unavailable in search results
```

**TC-1.9: Rapid sequential updates (same coordinator)**
```
Setup:   beds_total=10, beds_occupied=3, beds_on_hold=0 → beds_available=7
Action:  Five PATCH requests in rapid succession:
         Update 1: occupied=4  → expect available=6
         Update 2: occupied=5  → expect available=5
         Update 3: total=12    → expect available=7
         Update 4: occupied=3  → expect available=9
         Update 5: total=8     → expect available=5
Expect:  After each update, beds_available matches the formula exactly.
         The final snapshot is the last update (total=8, occupied=3, on_hold=0).
         beds_available = 5.
         
Risk:    The append-only model means 5 new rows are inserted. The DISTINCT ON
         query must consistently return the latest one. If timestamp resolution
         is too coarse (e.g., second-level precision), two rapid inserts may
         have the same snapshot_ts and the wrong one may be returned.
         
         CHECK: What is the precision of snapshot_ts? If it is second-level,
         rapid updates within the same second may produce non-deterministic
         ordering. It should be microsecond-level (PostgreSQL default for
         TIMESTAMPTZ is microsecond — verify this is not being truncated
         anywhere in the Java layer).
```

---

### GROUP 2: Reservation Interactions

These test the interaction between coordinator updates and outreach worker holds.

**TC-2.1: Hold placed against known availability**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=0 → beds_available=3
Action:  Outreach worker places a hold (POST /api/v1/reservations)
Expect:  beds_on_hold=1, beds_available=2 (decreased by exactly 1)
         A new snapshot is written with the updated on_hold count
         The reservation status is HELD
```

**TC-2.2: Hold confirmed (client arrives)**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=1 → beds_available=2
         One active HELD reservation
Action:  PATCH /api/v1/reservations/{id}/confirm
Expect:  beds_on_hold=0, beds_occupied=8, beds_available=2
         beds_available UNCHANGED — this is the critical assertion
         A held bed became an occupied bed. Available count must not change.
```

**TC-2.3: Hold cancelled (bed released)**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=1 → beds_available=2
         One active HELD reservation
Action:  PATCH /api/v1/reservations/{id}/cancel
Expect:  beds_on_hold=0, beds_available=3 (increased by exactly 1)
```

**TC-2.4: Hold expires (auto-release)**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=1 → beds_available=2
         One active HELD reservation with expires_at in the past
Action:  Expiry scheduler runs (or test-only endpoint triggers expiry)
Expect:  beds_on_hold=0, beds_available=3 (increased by exactly 1)
         Reservation status is EXPIRED
         
Risk:    The scheduler runs every 30 seconds. There is a window where the
         hold has expired by time but the scheduler hasn't fired yet. During
         this window, beds_available shows 2 when it should show 3.
         
         This is an inherent limitation of scheduled expiry. The question is:
         does the platform handle this correctly when the scheduler fires?
         And does the search result show the held bed as unavailable during
         the window (correct behavior) or available (incorrect)?
```

**TC-2.5: Hold placed when beds_available = 1 (last bed)**
```
Setup:   beds_total=10, beds_occupied=9, beds_on_hold=0 → beds_available=1
Action:  Outreach worker places a hold
Expect:  beds_on_hold=1, beds_available=0
         Shelter should appear as full in search results
         No further holds should be possible
```

**TC-2.6: Attempt to hold when beds_available = 0**
```
Setup:   beds_total=10, beds_occupied=10, beds_on_hold=0 → beds_available=0
Action:  Outreach worker attempts to POST /api/v1/reservations
Expect:  409 Conflict — no beds available
         beds_on_hold remains 0
         beds_available remains 0
         No snapshot is written (or snapshot is written but shows no change)
```

**TC-2.7: Coordinator updates while an active hold exists**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=1 → beds_available=2
         One active HELD reservation
Action:  Coordinator PATCHes availability:
         beds_total=10, beds_occupied=8, beds_on_hold=? 
         
         CRITICAL QUESTION: What does the coordinator send for beds_on_hold?
         
         Option A: Coordinator sends beds_on_hold=0 (they don't know about
                   the hold, or the UI doesn't show it as part of their input)
                   → beds_available = 10 - 8 - 0 = 2
                   But there is still an active HELD reservation!
                   The hold is now invisible in the calculation.
                   If the outreach worker confirms, beds_occupied becomes 9,
                   but the snapshot will show beds_occupied=9, beds_on_hold=0,
                   beds_available=1. Is that correct?
                   
         Option B: Coordinator sends actual beds_on_hold value (UI reflects
                   active holds from reservations)
                   → beds_available = 10 - 8 - 1 = 1 (correct)
         
         This is the most dangerous interaction in the system.
         The coordinator update must not be able to silently zero out active holds.
         
Expect:  The API must validate: if active reservations exist for this
         shelter/population_type, the beds_on_hold value in the PATCH
         must be >= the count of active HELD reservations.
         OR: beds_on_hold in the coordinator PATCH should be ignored/overridden
         to always reflect the actual count of active HELD reservations.
         
         Document the actual behavior. If it allows zeroing out active holds,
         that is a critical bug.
```

**TC-2.8: Coordinator decreases total beds while holds exist**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=2 → beds_available=1
         Two active HELD reservations
Action:  Coordinator PATCHes beds_total=8
Expect:  beds_available = 8 - 7 - 2 = -1 → VIOLATES INV-1

         What should happen?
         Option A: API rejects — cannot reduce total below occupied + on_hold
         Option B: API accepts — available goes negative (BUG)
         Option C: API accepts — clamps to 0, cancels holds automatically
         
         Document the chosen behavior and test it explicitly.
```

---

### GROUP 3: Concurrent Operations

This is where the most dangerous bugs live. Multiple actors operating on the
same shelter at the same time.

**TC-3.1: Two coordinators update simultaneously (different fields)**
```
Setup:   beds_total=10, beds_occupied=5, beds_on_hold=0 → beds_available=5

Coordinator A sends: beds_total=10, beds_occupied=6 (one new guest checked in)
Coordinator B sends: beds_total=12, beds_occupied=5 (two new beds added)

Both requests arrive within milliseconds of each other.

Possible outcomes:
  If A wins: total=10, occupied=6, available=4
  If B wins: total=12, occupied=5, available=7
  If A then B: total=12, occupied=5, available=7 (B's snapshot is latest)
  If B then A: total=10, occupied=6, available=4 (A's snapshot is latest)
  
  The append-only model means BOTH snapshots are written. The DISTINCT ON
  query returns the latest by snapshot_ts. 
  
  The problem: if A and B are processed within the same microsecond,
  snapshot_ts is identical. Which row does DISTINCT ON return?
  PostgreSQL's behavior with tied timestamps in DISTINCT ON is not guaranteed
  to be deterministic across executions.
  
Risk:   This is a real race condition. Two coordinators on the same shift
        could update simultaneously.

Test:   Run this scenario 100 times with artificial concurrent requests.
        Verify the result is always one of the valid states (A wins or B wins)
        and never an invalid state (negative available, mixed values from
        both requests).
        
        Also verify: after both requests complete, the GET shelter endpoint
        returns the same value consistently (no read inconsistency from
        cache serving stale data while new snapshot is being written).
```

**TC-3.2: Two outreach workers hold the last available bed simultaneously**
```
Setup:   beds_total=10, beds_occupied=9, beds_on_hold=0 → beds_available=1

Worker A and Worker B both attempt POST /api/v1/reservations simultaneously
for the same shelter and population type.

Expected outcome:
  Exactly one request succeeds (201 HELD)
  Exactly one request fails (409 Conflict)
  beds_on_hold = 1 (not 2)
  beds_available = 0 (not -1)
  
Risk:   This is the most critical concurrency scenario. If two holds succeed
        against one available bed, beds_available becomes -1 (INV-1 violated).
        An outreach worker transports a client to a shelter that has no room.
        
Test:   Use Karate's parallel runner to fire both requests truly simultaneously.
        Run 50 iterations. In every iteration:
        - Exactly 1 response is 201
        - Exactly 1 response is 409
        - beds_available is 0 (not -1)
        - beds_on_hold is 1 (not 2)
        
        This test is only meaningful if the two requests are genuinely in-flight
        at the same time. Sequential execution with milliseconds between them
        does not test this scenario.
```

**TC-3.3: Coordinator update races with outreach worker hold**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=0 → beds_available=2

Simultaneously:
  Coordinator A decreases total to 9 (maintenance on one bed)
  Worker B places a hold

Expected: One valid outcome from two possibilities:
  If coordinator wins first: total=9, occupied=8, on_hold=1 → available=0
  If worker wins first: total=10, occupied=8, on_hold=1, then total=9 → available=0
  
  Either way: available=0, occupied=8, on_hold=1
  
  DANGEROUS: If coordinator's update zeroes out beds_on_hold (see TC-2.7),
             the hold may be silently lost.
```

**TC-3.4: Simultaneous hold and confirm**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=1 → beds_available=1
         One existing HELD reservation (Reservation X)

Simultaneously:
  Worker A places a new hold on the 1 available bed
  Worker B confirms Reservation X (client arrived)

Expected after both complete:
  beds_occupied = 9 (Reservation X confirmed)
  beds_on_hold = 1 (Worker A's new hold)
  beds_available = 0
  
  DANGEROUS outcome: If both operations read the same snapshot and write
  independent updates:
  - Confirm writes: occupied=9, on_hold=0 → available=1
  - New hold writes: occupied=8, on_hold=2 → available=0
  
  The latest snapshot wins — but which is latest? And does either
  produce an invalid intermediate state that another read catches?
```

**TC-3.5: Three simultaneous holds against two available beds**
```
Setup:   beds_total=10, beds_occupied=8, beds_on_hold=0 → beds_available=2

Three outreach workers simultaneously attempt holds.

Expected:
  Exactly 2 responses are 201 (HELD)
  Exactly 1 response is 409 (Conflict)
  beds_on_hold = 2
  beds_available = 0
  
  NEVER: beds_on_hold = 3, beds_available = -1
```

---

### GROUP 4: Cache Correctness

The platform uses Caffeine L1 (60s TTL) with synchronous invalidation on write.
If invalidation fails or is incomplete, the displayed value is stale.

**TC-4.1: Cache invalidated synchronously on coordinator update**
```
Action:  Coordinator PATCHes availability (beds_occupied increases by 1)
Expect:  The PATCH response returns 200 with the updated available count
         Immediately after: GET /api/v1/shelters/{id} returns the same
         updated count (not the pre-update cached value)
         
         "Immediately" means the very next request, not after 60 seconds.
         
Risk:    If cache invalidation is async or deferred, there is a window where
         a coordinator updates and sees the old value on refresh — and stops
         trusting the platform.
```

**TC-4.2: Cache consistent across multiple GET requests**
```
Action:  After a coordinator update, make 10 rapid GET requests to
         /api/v1/shelters/{id}
Expect:  All 10 responses return the same, correct available count
         No request returns a stale pre-update value
```

**TC-4.3: Bed search (POST /api/v1/queries/beds) reflects updated availability**
```
Action:  Coordinator updates a shelter from available=0 to available=3
         Outreach worker immediately queries bed search
Expect:  The shelter appears in bed search results with beds_available=3
         It does not appear with 0 or remain absent from results
         
Risk:    The bed search may use a separate cache path from the shelter detail
         endpoint. A fix to one cache path may not fix the other.
```

**TC-4.4: data_age_seconds reflects actual update time**
```
Action:  A coordinator updated availability 5 minutes ago
Expect:  GET /api/v1/shelters/{id} returns data_age_seconds ≈ 300
         data_freshness = "AGING"
         
         The displayed available count is the one from 5 minutes ago.
         If nothing has changed, this is correct — the age indicator tells
         the outreach worker how fresh the data is.
```

---

### GROUP 5: Edge Cases and Boundary Conditions

**TC-5.1: First snapshot for a new shelter**
```
Setup:   Shelter created via POST /api/v1/shelters
         No availability snapshot exists yet
Action:  GET /api/v1/shelters/{id}
Expect:  beds_available is returned as null, 0, or with data_freshness=UNKNOWN
         NOT an error or 500
         The outreach worker sees a clear indication that no data is available
```

**TC-5.2: beds_on_hold updated by coordinator when active reservations exist**
```
Covered in TC-2.7 above. Flagged here as an edge case because it is the
most likely source of the reported incorrect calculation.
```

**TC-5.3: Population type mismatch**
```
Setup:   Shelter has SINGLE_ADULT beds_available=5 and FAMILY_WITH_CHILDREN
         beds_available=0
Action:  POST /api/v1/reservations for FAMILY_WITH_CHILDREN
Expect:  409 Conflict — no FAMILY_WITH_CHILDREN beds available
         SINGLE_ADULT availability is unaffected and unchanged
         
Risk:    If availability is calculated across population types rather than
         per population type, this could produce wrong results.
```

**TC-5.4: Overflow beds during surge mode**
```
Setup:   Surge event active, shelter reports overflow_beds=5
         Regular beds: total=10, occupied=10, on_hold=0 → regular_available=0
Action:  GET /api/v1/shelters/{id} and POST /api/v1/queries/beds
Expect:  beds_available=0 (regular) + overflow_beds=5 = 5 total available
         surgeActive=true in response
         
         Regular and overflow counts must not be mixed in the base calculation.
         beds_available is still 0 for regular beds.
         Overflow is a separate field.
```

**TC-5.5: Overflow beds reduced by coordinator after surge deactivation**
```
Setup:   Surge deactivated. Shelter still shows overflow_beds=5 in latest snapshot.
Action:  GET /api/v1/shelters/{id}
Expect:  overflow_beds is either ignored or shown with surgeActive=false indicator
         Outreach worker is not misled into believing overflow capacity exists
         when no surge is active
```

---

### GROUP 6: UI-to-API Consistency

These tests verify that what the coordinator sees in the UI matches what
the API actually holds, and that what the API holds is what the outreach
worker sees in search results.

**TC-6.1: Coordinator update form pre-populates correctly**
```
Setup:   beds_total=10, beds_occupied=7, beds_on_hold=2 → beds_available=1
Action:  Coordinator opens the availability update form for this shelter
Expect:  Form shows beds_occupied=7 as the current value (not 0, not stale)
         Form shows beds_total=10 as the current value
         Active holds (beds_on_hold=2) are either shown read-only or factored
         into the available count display
         
Risk:    If the form pre-populates from a stale cache or doesn't pre-populate
         at all, the coordinator may submit a value that produces wrong results.
         Example: form shows occupied=5 (stale), coordinator doesn't change it,
         submits 5, but actual occupied is 7. The snapshot records 5.
         beds_available = 10 - 5 - 2 = 3 (wrong, should be 1).
```

**TC-6.2: beds_on_hold in coordinator form**
```
Question: Can the coordinator directly set beds_on_hold in their form?
          Or is it automatically computed from active reservations?
          
          If the coordinator CAN set it manually, TC-2.7 is the critical risk.
          If the system computes it from reservations, verify that the
          coordinator's manual entry does not override the computed value.
          
          Document the actual behavior. Test it explicitly.
```

**TC-6.3: Search results consistent with shelter detail**
```
Action:  For any shelter, compare:
         - beds_available from POST /api/v1/queries/beds
         - beds_available from GET /api/v1/shelters/{id}
Expect:  Same value in both responses, from the same snapshot_ts
         
Risk:    If the two endpoints use different cache keys or different query
         paths, they may return different values for the same underlying data.
```

---

## What to Do With These Test Cases

**Step 1: Run each scenario manually against the current implementation.**
Document what the system currently produces. If it matches the expected
behavior, note it. If it does not, you have found a bug. Document the bug
clearly: what input, what output, what the correct output should be.

**Step 2: Prioritize the bugs.**
Group 3 (concurrent operations) and TC-2.7 (coordinator zeroing out active
holds) are the highest risk. TC-1.6 and TC-1.7 (total below occupied) are
likely the easiest to fix. Start with the most dangerous.

**Step 3: Fix the logic.**
Do not write tests against unfixed logic. Fix first.

**Step 4: Write the automated tests.**
For each bug found and fixed, write a test that:
- Reproduces the exact scenario that exposed the bug
- Asserts the correct behavior
- Would have failed before the fix
- Passes after the fix
- Will fail again if the logic regresses

**Step 5: Add invariant assertions.**
Consider adding an `AvailabilityInvariantChecker` that runs as a test utility,
callable from any integration test, that verifies INV-1 through INV-9 hold
for a given shelter after any operation. This makes future test writing faster
and catches invariant violations that individual test assertions might miss.

---

## A Note on Test Failures

When a test exposes a bug — especially in the concurrent scenarios — the
failure message must be informative enough to diagnose the problem without
a debugger. Include in assertion failures:

- The operation that was performed
- The shelter ID and population type
- The snapshot that was read (snapshot_ts, beds_total, beds_occupied, beds_on_hold)
- The expected beds_available
- The actual beds_available
- Any active reservations that existed at the time

A test that fails with `AssertionError: expected 3 but was -1` is not useful.
A test that fails with the full state of the system is a bug report.

---

## The Standard We Are Holding

An outreach worker at midnight does not know about snapshot_ts precision,
cache TTL, or concurrent write semantics. They see a number. They drive
toward it. If the number is wrong, a family sleeps in a parking lot.

That is the standard. Every one of these tests exists because that standard
demands it.

---

*Finding A Bed Tonight — QA Engineering*
*Riley Cho · Senior QA Engineer*
*github.com/ccradle/finding-a-bed-tonight*
