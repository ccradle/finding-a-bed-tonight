# Alertmanager Triage Runbook (v0.49+)

**Scope:** All 9 Prometheus alert rules currently loaded on findabed.org
(5 Phase B in `deploy/prometheus/phase-b-rls.rules.yml`, 4 Phase C in
`deploy/prometheus/phase-c-cache-isolation.rules.yml`). This runbook
covers the *delivery pipeline* and per-rule triage entry points; the
deeper Phase B / Phase C runbooks remain authoritative on protocol-
specific evidence (Phase B audit-write-failure protocol, Phase C cache-
isolation protocol).

**Audience:** Operator receiving an email or ntfy push from `fabt-demo`.

---

## Delivery pipeline (how you got paged)

```
Prometheus rule fires
   ↓
Alertmanager (rendered config at ~/fabt-secrets/alertmanager.yml on the
  VM, generated from deploy/alertmanager.yml.tmpl via envsubst)
   ↓ routing:
   ├── severity=critical → email_default (Gmail SMTP) AND ntfy_urgent (ntfy.sh push)
   └── severity=warning  → email_default only
```

**Group-by:** `alertname + tenant_id` — set in
`deploy/alertmanager.yml.tmpl:43`. *Caveat:* most current rules do not
carry a `tenant_id` label (the cross-tenant cache rule emits a
`tenant` label, not `tenant_id`), so in practice alerts of the same
`alertname` group together regardless of tenant. Tracked as a follow-
up to align rule labels with the Alertmanager grouping policy.

**Inhibit rule:** A firing critical suppresses warning of the same
`alertname` + `tenant_id` (`source_matchers: severity="critical"`,
`target_matchers: severity="warning"`, `equal: ['alertname',
'tenant_id']`). Same caveat as above re: `tenant_id` label presence.

**Timings:** `group_wait: 15s`, `group_interval: 5m`,
`repeat_interval: 4h`.

---

## The 9 rules (alphabetical by alertname)

### FabtPhaseBAuditPersistFailure (Phase B, WARNING)

**Source:** `deploy/prometheus/phase-b-rls.rules.yml`
**PromQL:** `sum by (action, sqlstate) (rate(fabt_audit_rls_rejected_count_total{sqlstate!="42501"}[10m])) > 0`
**Meaning:** Audit-row INSERT is failing with a non-RLS error (any
SQLState other than `42501`). Indicates schema / constraint /
connectivity problem; rows are dropped after the outer request has
already returned 2xx, so users do not see the failure.
**Runbook:** `docs/security/phase-b-silent-audit-write-failures-runbook.md`

**Triage:**
1. Identify the action + sqlstate from the alert labels.
2. `docker logs --since=15m fabt-backend 2>&1 | grep -i "AuditEventService\|persist.*audit"`
3. Check Postgres logs for matching SQLState — schema migration in
   flight, FK constraint, or transient connectivity will all surface
   here.

### FabtPhaseBForceRlsCleared (Phase B, CRITICAL)

**Source:** `deploy/prometheus/phase-b-rls.rules.yml`
**PromQL:** `fabt_rls_force_rls_enabled != -1 AND fabt_rls_force_rls_enabled == 0`
**Meaning:** One of the seven Phase B regulated tables reports
`relforcerowsecurity = 0`. Either a rogue migration cleared the flag
or `infra/scripts/phase-b-rls-panic.sh` ran. The `-1` guard avoids
paging during the gauge's first poll.
**Runbook:** `docs/security/phase-b-silent-audit-write-failures-runbook.md`

**Triage:**
1. Check `audit_events` for a `SYSTEM_PHASE_B_ROLLBACK` row in the same
   window — confirms the panic script was the trigger.
2. `docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "SELECT relname, relforcerowsecurity FROM pg_class WHERE relname IN ('audit_events','hmis_audit_log','hmis_outbox','kid_to_tenant_key','one_time_access_code','password_reset_token','tenant_key_material') ORDER BY relname;"`
3. All 7 must report `t`. Re-applying V69 restores the invariant; cross-
   check via `infra/scripts/phase-b-rls-snapshot.sh` Section 3.

**Rollback criterion:** Any table `f` after deploy = Phase B posture
regression, full rollback to last-good image via tag-swap.

### FabtPhaseBRlsRejection (Phase B, CRITICAL)

**Source:** `deploy/prometheus/phase-b-rls.rules.yml`
**PromQL:** `sum by (action, sqlstate) (rate(fabt_audit_rls_rejected_count_total{sqlstate="42501"}[10m])) > 0`
**Meaning:** Audit INSERT rejected by FORCE ROW LEVEL SECURITY (`42501`
= insufficient privilege). A write path is binding a tenant_id that
the RLS policy does not accept on the target table, OR the
connection's `app.tenant_id` GUC was cleared before the INSERT
executed (the classic `autoCommit=true` Bug D trap).
**Runbook:** `docs/security/phase-b-silent-audit-write-failures-runbook.md`

**Triage:**
1. Identify the action label.
2. Check `TenantContext` binding at the call site AND verify
   `@Transactional` wraps the `runWithContext` (B11 ordering invariant).
3. See runbook section "42501 rejection pattern".

### FabtPhaseBSystemTenantFallback (Phase B, WARNING)

**Source:** `deploy/prometheus/phase-b-rls.rules.yml`
**PromQL:** `sum by (action) (rate(fabt_audit_system_insert_count_total[15m])) > 0`
**Meaning:** `AuditEventService` could not resolve a tenant from
`TenantContext` or the session's `app.tenant_id` GUC and fell back to
the `SYSTEM_TENANT_ID` sentinel. After v0.43 + 7d, steady-state should
be zero — non-zero means a publisher is writing audit rows without a
`TenantContext` wrap.
**Runbook:** `docs/security/phase-b-silent-audit-write-failures-runbook.md`

**Triage:**
1. Inspect the `action` label.
2. Grep the codebase for `publishAuditEvent` calls with that action;
   wrap the call site in `runWithContext(tenantId, dvAccess, () -> ...)`.
3. See runbook section "Publisher forgot to bind TenantContext".

### FabtPhaseBTenantContextEmpty (Phase B, WARNING)

**Source:** `deploy/prometheus/phase-b-rls.rules.yml`
**PromQL:** `rate(fabt_rls_tenant_context_empty_count_total[15m]) > 1`
**Meaning:** JDBC connections borrowed while `TenantContext` is unbound
at a rate above the noise threshold (1/s). Upstream signal for
`SYSTEM_TENANT_ID` audit fallback. Some steady-state noise is expected
from scheduled jobs and application startup.
**Runbook:** `docs/security/phase-b-silent-audit-write-failures-runbook.md`

**Triage:**
1. Check what recently deployed — new scheduler, new SSE, new batch job?
2. `docker logs --since=30m fabt-backend 2>&1 | grep "tenant_context.empty"`
3. Phase C task #154 (label enrichment) will eventually attribute
   increments to source — until then, triage is forensic.

### FabtPhaseCCacheHitRateCollapse (Phase C, WARNING)

**Source:** `deploy/prometheus/phase-c-cache-isolation.rules.yml`
**PromQL:** Per-cache 1h hit rate < 50% of 7-day moving average (raw
PromQL division — see rule file for exact expression).
**Meaning:** A cache's hit rate has collapsed vs. its historical
baseline. Likely cause: a recent migration (task 4.b) landed with
divergent put-key vs. get-key producing mostly misses, OR a TTL was
shortened to a value smaller than the typical re-fetch interval.
**Runbook:** `docs/security/phase-c-cache-isolation-runbook.md`

**Triage:** See referenced runbook.

### FabtPhaseCCrossTenantCacheRead (Phase C, CRITICAL)

**Source:** `deploy/prometheus/phase-c-cache-isolation.rules.yml`
**PromQL:** `sum by (tenant, cache) (rate(fabt_cache_get_total{result="cross_tenant_reject",env="prod"}[5m])) > 0`
**Meaning:** `TenantScopedCacheService` value-stamp-and-verify rejected
a cached envelope stamped with a different tenant than the reader's
context. Refused to return it; audit row persisted via
`DetachedAuditPersister` (`PROPAGATION_REQUIRES_NEW`, survives caller
rollback).
**Runbook:** `docs/security/phase-c-cache-isolation-runbook.md`

**Triage:** See referenced runbook for the cache-specific protocol.
Cross-reference `audit_events` for `CROSS_TENANT_CACHE_READ` rows in
the same window.

### FabtPhaseCDetachedAuditPersistFailure (Phase C, WARNING)

**Source:** `deploy/prometheus/phase-c-cache-isolation.rules.yml`
**PromQL:** `sum by (action) (rate(fabt_audit_detached_failed_count_total{env="prod"}[15m])) > 0`
**Meaning:** `DetachedAuditPersister.persistDetached` swallowed a
persistence exception, losing a security-evidence audit row. Non-
CRITICAL because the caller's business logic continued, but the
forensic trail has a hole. Same posture as Phase B's audit-loss signal.
**Runbook:** `docs/security/phase-c-cache-isolation-runbook.md`

**Triage:**
1. `docker logs --since=60m fabt-backend 2>&1 | grep "DetachedAuditPersister failed for action="`
2. Stack trace + SQLState reveal whether this is (a) RLS rejection
   (42501 — connection bind broke), (b) FK constraint (actor_user_id →
   app_user — seed gap), or (c) DB connectivity.

### FabtPhaseCMalformedCacheEntry (Phase C, WARNING)

**Source:** `deploy/prometheus/phase-c-cache-isolation.rules.yml`
**PromQL:** `sum by (cache) (rate(fabt_cache_get_total{result="malformed_entry",env="prod"}[15m])) > 0`
**Meaning:** `TenantScopedCacheService` read a cached entry that is
NOT a `TenantScopedValue` envelope. A caller is writing raw values via
`CacheService.put`, bypassing the wrapper's stamp step. Family C
ArchUnit rule normally catches this at build time; non-zero past 4.b
landing indicates a test-path that escaped the rule.
**Runbook:** `docs/security/phase-c-cache-isolation-runbook.md`

**Triage:** See referenced runbook.

---

## When an alert fires: general procedure

1. **Acknowledge it in your head** — the goal of the alerting pipeline
   is wake-you-up; once awake, you can take your time.
2. **Check the Alertmanager UI** (SSH tunnel to `localhost:9093`): see
   the full alert list, find the alert in question, and follow the
   `runbook` annotation link from the rule definition (every rule above
   carries one).
3. **Check the Prometheus UI** (SSH tunnel to `localhost:9090`): graph
   the PromQL expression, see when it started firing, compare against
   deploy timestamp.
4. **Run the triage step** for the specific rule above.
5. **Silence if needed.** Use `amtool silence add` (docs at
   <https://prometheus.io/docs/alerting/latest/alertmanager/#silences>)
   with a time-bounded silence + a comment including your name + why.
6. **Document the incident.** Add a Slack post to `#fabt-demo` with the
   alert name + root cause + action taken as the minimum record.

---

## When NO alerts fire for an unexpectedly long time: sanity check

Absence of alerts is NOT proof that the delivery pipeline is healthy.
Check periodically:

1. Alertmanager container is running: `docker ps | grep alertmanager`
2. Prometheus sees Alertmanager in its targets:
   `curl -s http://localhost:9090/api/v1/alertmanagers | python3 -m json.tool`
3. Synthetic test-fire per the v0.49 deploy runbook post-deploy step is
   the canonical first-test; rerun on suspicion of pipeline silence.

---

## Change log

| Date | Change | Driver |
|---|---|---|
| 2026-04-21 | Initial runbook — documents the 9 rules + delivery pipeline shipped in v0.49.0. | v0.49 release-prep audit. |
