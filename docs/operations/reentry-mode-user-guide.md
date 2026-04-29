# Reentry Mode User Guide

**Audience:** People operating a tenant that has the
`features.reentryMode` flag enabled (or is about to). This guide
covers the platform operator who flips the flag, the CoC admin who
curates per-tenant configuration, and the outreach worker /
navigator who uses the resulting filters and hold-with-attribution
flow. It is operational, not marketing — if you are looking for the
external "what does reentry mode do" overview, that lives in the
v0.55.0 release notes.

**Scope:** Six topics, in the order an operator hits them when
turning the feature on for a tenant for the first time:

1. Enabling `features.reentryMode` for a tenant.
2. Curating `tenant.config.active_counties`.
3. Populating shelter `eligibility_criteria` via the guided form.
4. The outreach worker advanced-filters surface and the
   hold-with-attribution flow.
5. The 24-hour PII purge promise for hold-attribution columns.
6. `requires_verification_call=true` semantics for navigators.

The guide assumes the v0.55.0 backend + frontend are deployed (see
[`oracle-update-notes-v0.55.0.md`](../oracle-update-notes-v0.55.0.md)
for the deploy procedure itself; this guide does not duplicate it).

> **What this guide does NOT cover:** the deploy / rollback
> procedure (oracle-update-notes), the design rationale for the
> three-way `accepts_felonies` decision tree (covered in
> `openspec/changes/transitional-reentry-support/design.md` D1), or
> the PLATFORM_OPERATOR login + MFA flow itself (covered in
> [`platform-operator-user-guide.md`](./platform-operator-user-guide.md)).

---

## 1. Enabling `features.reentryMode` for a tenant

The flag is a boolean stored at JSONB path
`tenant.config.features.reentryMode`. It defaults to `false` for
every tenant existing at the v0.55 deploy (V91 seed); new tenants
created after v0.55 inherit the same default unless the create call
overrides. The flag gates **frontend visibility** of the reentry
surface — backend search and hold endpoints behave identically
regardless of the flag value (design D13). That asymmetry is
deliberate: it lets a CoC pilot the surface in a staging tenant
without forking backend behavior.

**Who can flip it:** the CoC admin for the target tenant via
`PUT /api/v1/tenants/{tenantId}/config`. The endpoint is gated by
`@PreAuthorize("hasRole('COC_ADMIN')")` plus a
`TenantPathGuard.requireMatchingTenant` check — so a CoC admin can
only flip the flag for their own tenant. See
[`backend/src/main/java/org/fabt/tenant/api/TenantConfigController.java`](../../backend/src/main/java/org/fabt/tenant/api/TenantConfigController.java)
lines 55-62.

The PLATFORM_OPERATOR can also flip the flag during initial
provisioning by writing the same `tenant.config` blob through the
SSH-tunnel access pattern. The auth + tunnel mechanics are covered
in [`platform-operator-user-guide.md`](./platform-operator-user-guide.md)
section 2 — they are not repeated here.

**The endpoint is full-replacement, not partial.** Any keys omitted
from the request body are removed. To add or change one key, GET
the current config first, merge client-side, then PUT the full map.
The OpenAPI description on
[`TenantConfigController.updateConfig`](../../backend/src/main/java/org/fabt/tenant/api/TenantConfigController.java)
lines 46-54 spells this out — read it before scripting against the
endpoint.

**Curl shape (CoC admin, JWT in `$JWT`):**

```bash
# 1. Read current config so we don't clobber other keys.
curl -s -H "Authorization: Bearer $JWT" \
  "$FABT_BASE_URL/api/v1/tenants/$TENANT_ID/config" \
  > /tmp/tenant-config.json

# 2. Merge features.reentryMode=true client-side. Use jq, Python,
#    your editor, or any tool that preserves JSON shape.
jq '.features.reentryMode = true' /tmp/tenant-config.json \
  > /tmp/tenant-config-next.json

# 3. PUT the full merged blob back.
curl -s -X PUT \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  --data-binary @/tmp/tenant-config-next.json \
  "$FABT_BASE_URL/api/v1/tenants/$TENANT_ID/config"
```

**Verification:** after a successful PUT, the next sign-in for any
user of that tenant routes through the reentry-aware search UI —
the advanced-filters `<details>` element is open by default, the
shelter-type chip group includes the reentry types, and the
admin shelter form shows the Eligibility Criteria fieldset. If
the surface is still hidden, check the response body of the GET in
step 1 — `features.reentryMode` should equal `true` (boolean, not
the string `"true"`).

**Audit trail:** the CoC admin path emits a
`TENANT_CONFIG_UPDATED` audit row with the key and old/new values
(see [`AuditEventType.java`](../../backend/src/main/java/org/fabt/shared/audit/AuditEventType.java)
lines 428-442). The PLATFORM_OPERATOR path emits a
`PLATFORM_TENANT_UPDATED` row instead (different event type
because intent differs — operational vs platform-grade). Both rows
are appended to the tenant's audit chain and survive caller
rollback per the standard audit posture.

---

## 2. Curating `tenant.config.active_counties`

`active_counties` is a per-tenant list of county-name strings that
controls two UI surfaces:

- **Outreach search advanced filters** — the County dropdown is
  populated from this list (see
  [`OutreachSearch.tsx`](../../frontend/src/pages/OutreachSearch.tsx)
  lines 764-797). When the list is empty or unset, the dropdown
  falls back to the NC 100-county default
  ([`NcCountyDefaults.java`](../../backend/src/main/java/org/fabt/shelter/county/NcCountyDefaults.java)).
- **Admin shelter form county field** — when the tenant has a
  populated list, the field renders as a single-select dropdown
  bound to the same source; when the list is empty, it falls back
  to a free-text input (see
  [`ShelterForm.tsx`](../../frontend/src/pages/ShelterForm.tsx)
  lines 645-685).

The resolved list is served by the read-only
`GET /api/v1/active-counties` endpoint, which any authenticated
user of the tenant can call (see
[`ActiveCountiesController.java`](../../backend/src/main/java/org/fabt/shelter/api/ActiveCountiesController.java)).
The endpoint deliberately omits a path-tenantId so cross-tenant
peeks are structurally impossible — a future PR adding a
PLATFORM_OPERATOR variant must re-apply the cross-tenant guard.

**Two write paths:**

- **CoC admin** writes via `PUT /api/v1/tenants/{tenantId}/config`
  (same endpoint as section 1; merge client-side, PUT the full
  blob). Per the v0.55 SecurityConfig revision, the COC_ADMIN role
  on the controller now reaches their own tenant's config without
  hitting the prior "platform-only" rejection.
- **Platform operator** seeds or overrides during tenant creation
  via the same endpoint over the SSH tunnel. Useful for non-NC
  deployments where the default 100-county fallback would surface
  irrelevant entries.

**Curl shape (set the list to three counties):**

```bash
# Get-merge-put pattern, same as the reentryMode flag flip.
curl -s -H "Authorization: Bearer $JWT" \
  "$FABT_BASE_URL/api/v1/tenants/$TENANT_ID/config" \
  > /tmp/tenant-config.json

jq '.active_counties = ["Buncombe", "Henderson", "Madison"]' \
  /tmp/tenant-config.json > /tmp/tenant-config-next.json

curl -s -X PUT \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  --data-binary @/tmp/tenant-config-next.json \
  "$FABT_BASE_URL/api/v1/tenants/$TENANT_ID/config"
```

**Empty list vs unset:** an empty array (`[]`) is treated
distinctly from an unset key — empty means "no validated counties,
fall back to free-text," whereas unset means "fall back to the NC
default." If the deployment is not in North Carolina, set an
explicit list (even of one entry) rather than leaving the key
unset, or every shelter admin gets a 100-entry NC dropdown that
does not match their service area.

**Validation:** `shelter.county` writes are validated against
`active_counties` at the application layer
([`ShelterService.java`](../../backend/src/main/java/org/fabt/shelter/service/ShelterService.java)).
There is no DB-level enum (design D3) — adding a new county means
one PUT call, not a Flyway migration. Existing shelter rows whose
county is no longer in the list are not invalidated; the constraint
fires only at write time.

---

## 3. Populating `eligibility_criteria` via the guided form

CoC admins populate the per-shelter `eligibility_criteria` JSONB
through the guided form section in
`Admin → Shelters → edit shelter → Eligibility Criteria`. The
section is rendered by
[`EligibilityCriteriaSection.tsx`](../../frontend/src/components/EligibilityCriteriaSection.tsx)
and is COC_ADMIN-only via the `visible` prop the parent form sets.

The section organizes data into four subsections.

### 3.1 Criminal record policy

Three fields, all optional:

- **Accepts felonies** — tri-state radio group: `Yes` / `No` /
  `Not specified`. The tri-state matters because the search-time
  evaluator (see section 4) differentiates explicit-false from
  absent-data. The `Not specified` option writes `null`, not
  `false`. Test ids:
  `accepts-felonies-true` / `accepts-felonies-false` /
  `accepts-felonies-unset`.
- **Excluded offense types** — multi-select chip group of 6
  controlled values (sex offense, violent offense, etc.). Each
  chip is `aria-pressed` toggleable. Test id:
  `excluded-offense-types-{TYPE}`.
- **Notes** — free-text textarea, 500-char cap.

A non-dismissable disclaimer renders above these fields whenever
the section is visible
([`CriminalRecordPolicyDisclaimer.tsx`](../../frontend/src/components/CriminalRecordPolicyDisclaimer.tsx)).
A CI guard at
`scripts/ci/check-criminal-record-disclaimer-co-rendering.sh`
enforces the co-rendering: any frontend file that names
`criminal_record_policy`, `accepts_felonies`, or
`excluded_offense_types` in non-comment code must also render the
disclaimer. The text itself is i18n-keyed
(`shelter.criminalRecordPolicyDisclaimer`) and is reworded only
through a Casey Drummond legal re-review — operators must not edit
it inline.

### 3.2 VAWA note

A single checkbox, **VAWA protections apply**. When checked:

- The form-level note `shelter.vawaProtectionsApplyNote` renders
  inline below the checkbox (admin-facing — explains what enabling
  it does).
- The disclaimer above the criminal-record fields adds a second
  paragraph with `shelter.vawaNoteDisclaimer` (navigator-facing —
  shown alongside the data on outreach search cards).

The two strings are deliberately distinct (warroom M3); they read
coherently in either order so a screen reader announcing the
disclaimer first hits the universal posture before the
case-specific note.

### 3.3 Program requirements / documentation required

Two tag-list editors driven by the shared `TagEditor` component.
Free-text per tag, no controlled vocabulary. Use these for
program-specific labels not covered by the criminal-record fields
(e.g., `Sobriety check at intake`,
`Photo ID required (any state)`). Test ids:
`program-requirements-tag-editor`,
`documentation-required-tag-editor`.

### 3.4 Intake hours

Single free-text input, 200-char cap. Free-text is intentional —
"M-F 8am-5pm, Sat 9am-noon, closed Sundays" does not survive a
controlled-vocabulary schema, and standardizing intake-window
schemas is a separate body of work the v0.55 release does not take
on.

### 3.5 Wire format note

The shelter create / edit POST and PATCH payloads expect the
`eligibilityCriteria` field as a JSON-encoded string per the
backend `JsonString` deserializer contract (see
[`EligibilityCriteria.java`](../../backend/src/main/java/org/fabt/shelter/domain/EligibilityCriteria.java)
+ the `ShelterConstraintsDto` field annotation). The UI
serializes this transparently — operators do not need to think
about the wire format. Direct API callers (HMIS importers,
scripted bulk-update tools) must do the JSON-string wrap
themselves; the structured-object form is a convention of the
guided UI only.

---

## 4. Outreach workers and navigators: filters and hold flow

The outreach search surface
([`OutreachSearch.tsx`](../../frontend/src/pages/OutreachSearch.tsx))
gains three filter controls, a per-result badge, and a redesigned
hold-creation flow when the tenant has `features.reentryMode=true`.

### 4.1 Advanced filters

The advanced-filters `<details>` element is **open by default** on
desktop (warroom M4). On mobile the user can collapse it with one
tap. An "active count" badge on the summary line surfaces how many
filters are currently set, so a collapsed-then-forgotten filter
does not become invisible state. Test id:
`advanced-filters-active-count`.

Three filter controls, in DOM order:

- **Shelter type chip group** — multi-select chips for
  `EMERGENCY`, `TRANSITIONAL`, `REENTRY_TRANSITIONAL`,
  `PERMANENT_SUPPORTIVE`, `RAPID_REHOUSING`,
  `SUBSTANCE_USE_TREATMENT`, `MENTAL_HEALTH_TREATMENT`, and `DV`.
  The `DV` chip is hidden for users without `dvAccess=true` —
  filtering by DV without dvAccess would yield an empty result via
  RLS, which is confusing UX (warroom H1). Test id:
  `shelter-type-filter-{TYPE}`.
- **County dropdown** — single-select. Populated from the
  `active-counties` endpoint (section 2). Empty value means "any
  county." A loading state surfaces while the list is fetched
  (warroom S2). Test id: `county-filter`.
- **Accepts-felonies toggle** — single ToggleChip. When on, the
  search excludes shelters whose `eligibility_criteria` indicates
  `accepts_felonies=false`, and shelters with no eligibility data
  unless they have `requires_verification_call=true`. The
  three-way logic lives in
  [`AcceptsFeloniesEvaluator.java`](../../backend/src/main/java/org/fabt/availability/service/AcceptsFeloniesEvaluator.java)
  lines 70-80. When the toggle is on AND the result list is empty,
  a hint banner explains the "we have no data on most shelters"
  case rather than letting the operator misread it as "no shelters
  accept" (test id: `accepts-felonies-empty-banner`).

### 4.2 Search result cards

Each result card now shows three new pieces of information:

- **Shelter type badge** (test id:
  `shelter-type-display-{shelterId}`) — i18n-keyed under
  `shelter.type.{TYPE}`.
- **County label** alongside the address.
- **"Requires verification call" badge** (test id:
  `requires-verification-call-badge-{shelterId}`) — only renders
  when the shelter has `requires_verification_call=true`. See
  section 6 for the navigator-facing semantics.

### 4.3 Hold-with-attribution flow

Clicking the hold-bed chip on a result card now opens a modal
dialog ([`HoldDialog.tsx`](../../frontend/src/components/HoldDialog.tsx))
rather than firing the POST instantly. The modal defaults to
no-attribution mode:

- The Confirm button is auto-focused on open.
- The attribution section is collapsed inside a `<details>`
  element (warroom M1).
- A routine no-attribution hold is one keystroke: press Enter to
  confirm. (Pre-§11 design was zero keystrokes; the warroom
  decision was that the one-keystroke cost is worth the
  attribution affordance.)

To attach client details, the operator opens the
`<details>` section. Inside:

- A privacy note (`hold.clientAttributionPrivacyNote`) renders at
  the top of the open section — Casey-reviewed copy describing
  what the platform will and will not retain. Test id:
  `hold-attribution-privacy-note`.
- **Held for client name** — free-text input, 100-char cap.
- **Held for client DOB** — date input. Validated layered:
  - HTML5 `min="1900-01-01"` and `max={today}` on the input.
  - In-browser JS guard before submit returns a localized error if
    DOB is below the 1900 floor or in the future.
  - Backend service-layer validation also enforces the 1900 floor
    so direct API callers cannot bypass.
- **Notes** — free-text textarea, 500-char cap.

All three attribution fields are optional and independently
settable. The dialog returns focus to the chip-button that opened
it on close (WCAG 2.4.3) and traps Tab/Shift-Tab inside the dialog
form (APG dialog pattern). Esc cancels.

If the operator submits with attribution, the values flow as
ciphertext into the V93 `held_for_client_*_encrypted` columns,
encrypted under the tenant's `RESERVATION_PII` purpose-keyed DEK.
Section 5 covers when the ciphertext is purged.

### 4.4 Coordinator dashboard view

Coordinators viewing held reservations on
[`CoordinatorDashboard.tsx`](../../frontend/src/pages/CoordinatorDashboard.tsx)
see the held-for-client name surfaced inline on the hold row when
the outreach worker entered it. DOB and notes are not displayed on
the dashboard list — they are decryptable via the reservation
detail endpoint when a coordinator needs them, but the list view
is intentionally terse to limit incidental PII exposure.

---

## 5. The 24-hour PII purge promise

The hold-attribution PII columns are nulled 24 hours after a
reservation reaches a resolved state. This is the
24-hour-post-resolution defense; the at-rest defense is
crypto-shred via `tenant_dek` + the `RESERVATION_PII` purpose-key
family. The two layers are independent — one fires on tenant
hard-delete, the other on per-reservation resolution age.

**What gets nulled:**

- `reservation.held_for_client_name_encrypted`
- `reservation.held_for_client_dob_encrypted`
- `reservation.hold_notes_encrypted`

**What is preserved:** every other column on the row — the
reservation id, shelter id, population type, status timestamps,
user id, idempotency key, and the resolution outcome itself.
Analytics queries and audit trails continue to work.

**When it fires:** an hourly Spring `@Scheduled` job
(`ReferralTokenPurgeService.purgeExpiredHoldAttribution`, see
[the service](../../backend/src/main/java/org/fabt/referral/service/ReferralTokenPurgeService.java)
lines 87-99) calls
`ReservationService.purgeExpiredHoldAttribution(cutoff)` with
`cutoff = now - 24h`. The repository SQL nulls rows matching
either:

- `status IN (CANCELLED, CONFIRMED, EXPIRED, CANCELLED_SHELTER_DEACTIVATED)`
  AND `created_at < cutoff`; OR
- `expires_at < cutoff` (catches HELD rows whose expiry passed but
  the reaper has not yet stamped status=EXPIRED).

The SQL is no-op on rows whose ciphertext is already null, so
re-runs cost nothing.

**Verification:** the purge job logs at INFO when it nulls rows
(`purgeExpiredHoldAttribution: purged=N`) and DEBUG when it nulls
none (`purged=0`). Operators should see one log line per hour per
backend instance.

The current implementation does NOT emit a per-purge audit row —
the `audit_events` chain is not the verification surface for this
job. If a per-row audit trail becomes necessary (compliance review,
incident forensics), that is a follow-up rather than a current
behavior. Operators verifying the purge today should rely on the
log line plus a direct DB read of `reservation` rows where
`status IN (...)` AND `created_at < now() - interval '24 hours'`
AND any `*_encrypted` column is non-null — that count should be
zero on a healthy system.

**Why crypto-shred AND row-level null:** the row-level null defends
against operational read paths (a developer running an ad-hoc
query, an admin dump, a backup restore) that bypass the DEK
unwrap. The crypto-shred defends against on-disk forensics and
backup-tape recovery on a tenant that no longer exists. Either
alone leaves a gap; both together close it.

---

## 6. `requires_verification_call=true` semantics

The `shelter.requires_verification_call` boolean, set in the admin
shelter form (V91 column), drives a single advisory annotation on
the outreach search surface and one piece of decision logic in the
backend search filter.

**What it shows the navigator:**

- A "Requires verification call" badge renders on the result card
  when the flag is true (test id:
  `requires-verification-call-badge-{shelterId}`). The badge is
  visual + text — no color-only encoding, WCAG-clean.
- The badge does NOT change inclusion in the result list (no
  auto-filter). It is purely advisory: "before you transport a
  client here, call the shelter to confirm intake."

**What it does in the backend:**

- When the search caller passes `acceptsFelonies=true`, the
  three-way evaluator at
  [`AcceptsFeloniesEvaluator.java`](../../backend/src/main/java/org/fabt/availability/service/AcceptsFeloniesEvaluator.java)
  uses `requires_verification_call` to decide branch (c) — the
  any-null path. A shelter with no eligibility data is INCLUDED
  iff `requires_verification_call=true`, otherwise EXCLUDED. The
  rationale: we prefer to over-show with a "call to verify"
  annotation than silently drop a shelter from results because of
  a data-quality regression on a single record.
- Outside the `acceptsFelonies=true` path, the flag has no effect
  on inclusion.

**Interaction with `accepts_felonies`:** the two fields are
independent. A shelter can set `requires_verification_call=true`
AND `accepts_felonies=true` (advisory: call to confirm bed
availability even though policy is felony-friendly). It can set
`requires_verification_call=true` AND `accepts_felonies=false` (do
not transport — but if you call, the shelter may have a referral
to a sister program). It can set the flag in either direction
independently of the criminal-record policy entirely.

**Navigator workflow when the badge is set:**

1. Read the rest of the card to confirm the shelter looks like a
   match (population type, county, capacity).
2. Call the shelter at the listed phone number.
3. Confirm verbally that intake is open and a bed is available.
4. Place the hold (with or without client attribution).
5. Transport the client.

The verification call is a workflow advisory — it is not enforced
by the system, and the platform does not block hold creation on a
non-verified shelter. Outreach worker training should reinforce
that the badge means "call first."

---

## See also

- [`platform-operator-user-guide.md`](./platform-operator-user-guide.md)
  — login + MFA + dashboard for the PLATFORM_OPERATOR role.
- [`oracle-update-notes-v0.55.0.md`](../oracle-update-notes-v0.55.0.md)
  — operator-side deploy procedure, two-stage rollout, rollback
  recipe for the v0.55 release.
- [`runbook.md`](../runbook.md) — top-level operator runbook
  covering general FABT operations.
- `openspec/changes/transitional-reentry-support/` (in the docs
  repo) — design rationale, warroom decisions, and slice-by-slice
  task tracking. Read this before making behavior changes; the
  load-bearing decisions (D1 three-way evaluator, D3 no-DB-enum
  for counties, D4 two-layer PII posture, D6 disclaimer
  co-rendering, D13 frontend-only flag gate) live in `design.md`.
