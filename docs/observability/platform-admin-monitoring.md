# Platform Admin Monitoring (G-4.5 §6.19)

**Audience:** Operator / SOC engineer running observability for the
platform-operator surface. Documents what metrics + log markers v0.53
emits, what alerts they drive, and what Grafana panels are worth
building when an observability pass lands (Phase H+).

**Companion artifacts:**
- `deploy/prometheus/phase-g-platform-admin.rules.yml` — alert rules
- `docs/security/dv-incident-response.md` — DV-side runbook (parallel pattern)
- `openspec/changes/platform-admin-split-and-access-log/design.md` F28
  (deferred FabtPlatformUserDelayedActivation gauge)

---

## What v0.53 emits

### Counters (incremented at the platform-auth surface)

| Metric | Tags | Source | When it fires |
|---|---|---|---|
| `fabt.platform.login.failures` | `reason` ∈ {bad_email, locked, bad_password, mfa_disabled} | `PlatformAuthService.login` + `verifyMfa` | Any REJECTED login outcome. Rejected paths run a decoy bcrypt verify so timing is constant — but the counter carries the actual reason for forensic / alert use. |
| `fabt.platform.user.locked_out` | (none) | `PlatformAuthService.recordFailureAndMaybeLock` | Lockout TRANSITION only (not per failed MFA attempt). 5 fails / 15 min auto-locks per V88 policy. |
| `fabt.platform.action.without_justification` | `action` (the `AuditEventType` of the would-be platform action) | `PlatformAdminLogger.aroundPlatformAdminOnly` | Defense-in-depth: aspect saw a request without `X-Platform-Justification`. Should always be 0 — non-zero means `JustificationValidationFilter` is offline / mis-ordered. |
| `fabt.platform.admin.action` | `action`, `outcome` ∈ {committed, method_failed_after_audit, aspect_failed} | `PlatformAdminLogger.emitMetric` (G-4.3) | Per-action audit-write outcome; existing G-4.3 metric, listed here for completeness. |

### Log MDC marker

All log lines emitted during a `@PlatformAdminOnly` invocation carry
the SLF4J MDC entry `platform_action=true`. This is set at aspect
entry (`PlatformAdminLogger.aroundPlatformAdminOnly`) and removed in
the `finally` so child log statements within the wrapped business
method also inherit it.

The `PlatformAdminAccessLogger.logLockout` path (V88 lockout audit,
which fires from `PlatformAuthService` outside the aspect) sets the
same marker explicitly so SOC log filters catch the full
platform-action surface.

**SOC filter expression** (Loki / OpenSearch / etc.):
```
{platform_action="true"}
```

### Audit-events rows (forensics, not alerting)

Every `@PlatformAdminOnly` method commits one `audit_events` row +
one `platform_admin_access_log` row before the business method runs
(G-4.3 D5). These are NOT exposed as Prometheus metrics — they are
the queryable forensic surface. To pull them, use the owner-role
psql pattern from `docs/security/dv-incident-response.md` step 3
(set `app.tenant_id` to the SYSTEM tenant + `app.dv_access='true'`).

---

## Active alert rules (phase-g-platform-admin.rules.yml)

| Alert | Severity | Threshold | Runbook anchor |
|---|---|---|---|
| `FabtPlatformLoginFailureBurst` | warning | rate > 5/min sustained 2 min | This file → "Login failure burst" |
| `FabtPlatformUserLockedOut` | info | rate > 0 over 5 min | This file → "Account lockout" |
| `FabtPlatformActionWithoutJustification` | critical | rate > 0 over 5 min | This file → "Filter chain broken" |

The fourth spec'd alert — `FabtPlatformUserDelayedActivation` — is
deferred to F28 because it requires a new SECURITY DEFINER function
(`platform_user_count_unactivated_older_than(seconds)`) and a
scheduled gauge. The operational gap is partially covered by
`PlatformUser.isLoginAllowed()` enforcement (an unactivated user
cannot log in) and the `oracle-update-notes-v0.53.0.md §5.10`
activation checklist.

---

## Grafana panel ideas (Phase H+)

These are sketches for when an observability pass lands. Not built
in v0.53; the metrics are emitted so the panels can be added without
backend changes.

### Panel 1 — Platform login funnel
**Query stack** (single panel, multiple time-series):
```promql
# Successful logins (no per-success counter today; derive from
# failures + total — until then, just plot the failure rate)
sum by (reason) (rate(fabt_platform_login_failures_total[5m])) * 60
```
**Visualization:** stacked area chart, by `reason`. Heavy `bad_email`
is enumeration; heavy `bad_password` is brute force; heavy `locked`
is post-attack (someone is hitting locked accounts hoping for a
race-condition unlock).

**Future enhancement:** add `fabt.platform.login.success` counter so
the panel can plot conversion ratio.

### Panel 2 — Lockouts over time
```promql
sum(rate(fabt_platform_user_locked_out_total[1h])) * 3600
```
**Visualization:** single-series line chart with annotations for
incident-response timeline. Hover should show the timestamp of each
spike for cross-reference with `audit_events.timestamp` queries.

### Panel 3 — Platform action volume by operator action
```promql
sum by (action) (rate(fabt_platform_admin_action_total{outcome="committed"}[15m])) * 60
```
**Visualization:** stacked bar chart, grouped by `action`
(`PLATFORM_TENANT_UPDATED`, `PLATFORM_TENANT_OBSERVABILITY_UPDATED`,
`HMIS_EXPORT_TRIGGERED`, etc.). Useful baseline for "what does
normal platform-operator activity look like?" — answers questions
like "is anyone running tenant-update at 3am?"

### Panel 4 — Audit chain coverage
```promql
sum by (outcome) (rate(fabt_platform_admin_action_total[5m])) * 300
```
**Visualization:** four-line chart showing committed / method_failed_after_audit
/ aspect_failed counts. Healthy: only `committed` is non-zero. Either
of the failure modes warrants investigation (G-4.3 D5 contract: the
audit row commits BEFORE the business method runs, so a
`method_failed_after_audit` means the operator-facing 5xx came from
the business method, not the audit layer).

### Panel 5 — Defense-in-depth canary
```promql
sum by (action) (rate(fabt_platform_action_without_justification_total[1h])) * 3600
```
**Visualization:** single-series sparkline pinned at 0. Any non-zero
value means `JustificationValidationFilter` is offline — pair with
the `FabtPlatformActionWithoutJustification` critical alert.

### Panel 6 — DV-defense bridge (cross-reference)
```promql
sum by (source_ip) (rate(fabt_dv_referrals_created_total[5m])) * 60
```
Same data as the DV-defense panel in `dv-incident-response.md`,
pinned next to platform-admin panels because operators triaging
either kind of attack often want to see both surfaces at once
(coordinated attack on tenant data + platform credentials is the
"both layers compromised" scenario).

---

## Grafana dashboard JSON skeleton (when ready to build)

Save as `infra/grafana/dashboards/platform-admin.json`. Structure:
```json
{
  "title": "Platform Admin (G-4.5)",
  "tags": ["fabt", "platform", "auth", "g-4.5"],
  "panels": [
    { "title": "Login funnel by reason", "datasource": "Prometheus", "targets": [...] },
    { "title": "Lockouts (hourly)", ... },
    { "title": "Platform action volume", ... },
    { "title": "Audit chain coverage", ... },
    { "title": "Defense-in-depth canary", ... },
    { "title": "DV-defense bridge", ... }
  ],
  "time": { "from": "now-24h", "to": "now" },
  "refresh": "30s"
}
```

The skeleton is intentionally not committed yet — Grafana JSON is
verbose, version-controlled separately when we have a Grafana
instance to source-of-truth against.
