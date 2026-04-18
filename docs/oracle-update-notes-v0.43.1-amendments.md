# Amendments to `oracle-update-notes-v0.43.0.md` for the v0.43.1 deploy

> **Read alongside** `docs/oracle-update-notes-v0.43.0.md` (which is frozen in
> the v0.43.1 tag). The base procedures are unchanged; this file captures
> deltas learned from the v0.42.0 → v0.42.1 hotfix on 2026-04-18.

**Target tag:** `v0.43.1` (commit `624f9a1`).
**Deploying from previous state:** `v0.42.1` (shipped 2026-04-18 evening — NOT
v0.42.0, which failed mid-migration and was superseded).

## Substitutions to apply while reading the base notes

Throughout `oracle-update-notes-v0.43.0.md`, substitute:

| Base text | Replace with |
|---|---|
| `v0.43.0` (in `git checkout`, `jar tf`, `docker build`, JAR filename) | `v0.43.1` |
| `finding-a-bed-tonight-0.43.0.jar` | `finding-a-bed-tonight-0.43.1.jar` |
| `From: v0.42.0 (Phase 0 + A + A5, deployed Sunday 2026-04-19)` | `From: v0.42.1 (Phase 0 + A + A5 + V74 plaintext-tolerance, deployed Saturday 2026-04-18)` |
| `CHANGELOG [v0.43.0]` | `CHANGELOG [v0.43.1]` (legal-scan reword only; scope identical to 0.43.0) |

The content difference between `v0.43.1` and `v0.43.0` is a single CHANGELOG
wording fix to address a legal-language-scan flag. No migration, code, or
operational changes.

## 24-hour bake-window — operator decision required

Base notes say:
> v0.42 must have baked cleanly for ≥ 24h before tagging this release.

v0.42.1 deployed Saturday 2026-04-18 ~19:00 local. If the v0.43.1 deploy
starts before Sunday 2026-04-19 ~19:00, we are inside the 24h window. This is
a deviation from the warroom guardrail and requires an explicit operator waiver
(same precedent as the compressed Sat–Tue cadence warroom decision).

**Operator action:** document waiver in the Slack #fabt-demo thread or skip
until 19:00 local to respect the guardrail.

## Script-availability ordering — READ THIS FIRST

Phase 0/A/A5 scripts and Phase B scripts live on **different tags**:

| Script | `v0.42.1` tree | `v0.43.1` tree |
|---|---|---|
| `scripts/phase-0-a-a5-smoke.sh` | ✅ present | ❌ missing |
| `scripts/phase-b-rls-panic.sh` | ❌ missing | ✅ present |
| `scripts/phase-b-rehearsal.sh` | ❌ missing | ✅ present |
| `scripts/phase-b-audit-path-smoke.sh` | ❌ missing | ✅ present |
| `scripts/phase-b-rls-snapshot.sh` | ❌ missing | ✅ present |

Consequence: the pre-deploy checklist spans both trees. Run in this order:

1. **On the v0.42.1 working tree (no checkout yet):**
   - Base notes pre-deploy #1 — `scripts/phase-0-a-a5-smoke.sh`
   - Amendment — NULL-tenant verify query
   - Amendment — prometheus.yml grep
2. **`git checkout v0.43.1`**
3. **On the v0.43.1 working tree:**
   - Base notes pre-deploy #3 — `scripts/phase-b-rehearsal.sh`
   - Base notes pre-deploy #4 — `scripts/phase-b-rls-snapshot.sh`
   - Base notes pre-deploy #5 — `scripts/phase-b-rls-panic.sh --dry-run`
   - Amendment — Alertmanager rules-loaded check

**Rollback ordering implication:** the panic script lives on the v0.43.1
tree. If you need to roll back after deploy, run `phase-b-rls-panic.sh`
**BEFORE** `git checkout v0.42.1` — the checkout will remove the script.
Base notes' rollback procedure already has the right ordering; just don't
shortcut it.

## Pre-Deploy Checklist — updates

### Step 2 (NULL-tenant audit backfill)

Already completed on 2026-04-18 for the v0.42 deploy. Four rows were archived
to JSONL and UPDATE'd to `SYSTEM_TENANT_ID`. No re-work expected.

Verify only, do NOT re-archive:
```bash
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT COUNT(*) FROM audit_events WHERE tenant_id IS NULL; \
     SELECT COUNT(*) FROM hmis_audit_log WHERE tenant_id IS NULL"
# Expect: 0 and 0. If > 0, investigate — a code path is writing NULL tenant_id post-backfill.
```

### New pre-deploy verification — prometheus.yml scrape target

During the v0.42.0 → v0.42.1 triage, the VM working tree was rolled back to
commit `41bd162`, which reverted `prometheus.yml` to a broken scrape target
(`host.docker.internal:9091` — does not resolve inside the compose network).
The v0.42.1 oracle-update-notes flagged this as an additional step. Confirm
it's still applied before v0.43 deploy; the post-deploy check #5 depends on a
working scrape.

```bash
grep -n 'backend:9091\|host.docker.internal:9091' ~/finding-a-bed-tonight/deploy/prometheus/prometheus.yml
# Expect: at least one line with 'backend:9091' (scrape target inside compose network).
# If the only match is 'host.docker.internal:9091', re-apply the fix BEFORE deploy.
```

### Step 6 (Prometheus + Alertmanager routing)

Slack Alertmanager wiring for the 5 Phase B alert rules is on the backlog and
is **not a release blocker**. Downgrade this release-gate to:

```bash
# Verify the 5 Phase B alert rules LOADED in Prometheus (no routing test).
curl -s http://localhost:9090/api/v1/rules \
    | jq '.data.groups[] | select(.name | startswith("phase_b")) | .rules | length'
# Expect: 5.
```

Leave the Slack routing synthetic-trigger test for the follow-up backlog
sprint that tackles Alertmanager wiring.

## Post-Deploy Sanity Checks — additions

### Check #6 (new) — append-only audit-table revocations are live

Already included as check #3 in base notes. No change; just reiterating because
V70 + V72 (REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES) are the forensic-table
teeth of Phase B — if they regressed, FORCE RLS alone doesn't protect the
tables from `fabt_app` tampering.

### Running Playwright post-deploy smoke (optional but recommended)

Base notes reference `scripts/phase-b-audit-path-smoke.sh` (5-step) as the
canonical post-deploy. After v0.42.1 we also ran the Playwright suite:

```bash
cd e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test post-deploy-smoke --project chromium --trace on \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.43.1.log
```

**Known Test 6 (notification count endpoint) flake**: bucket4j auth rate-limit
can cascade across rapid-fire logins and cause a 429 that registers as a test
failure. Confirm by manually logging in as `dv-coordinator@dev.fabt.org` on
`https://findabed.org` — if that works, the test failure is the rate-limit
cascade, not a real regression.

### Active-watch additions (for the 4-hour window)

On top of the 6 metrics listed in base notes, also watch for:

- **Hikari connection-reuse leak (W-B-FIXA-1)** — if `tenantKeyRotationService`
  runs during the watch window (rare), confirm subsequent connection borrows
  do not carry stale `app.tenant_id` or `is_local=false` bindings. Canonical
  check: `SELECT pg_backend_pid(), current_setting('app.tenant_id', true)` from
  a sampled connection. Expect empty string on a fresh borrow. IT for this
  leak is deferred — this is observational defense-in-depth.

## Rollback — no change

Panic-script + revert-to-v0.42.1 container procedure unchanged from base notes.
Substitute `v0.42.1` for `v0.42.0` in the "Revert backend container" step.

## Related

- `docs/oracle-update-notes-v0.43.0.md` — base procedures (frozen in `v0.43.1` tag)
- `docs/oracle-update-notes-v0.42.1.md` — predecessor deploy notes + prometheus.yml addendum
- `CHANGELOG.md` — `[v0.43.1]` section (legal-scan reword)
