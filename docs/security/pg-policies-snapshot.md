# pg_policies snapshot — Phase B (V67–V72 applied)

> **This file is a template + expected-shape reference.**
>
> Regenerate against a live environment before tagging v0.43:
>
> ```bash
> FABT_PG_OWNER_URL=postgresql://fabt:PASS@localhost/fabt \
>     scripts/phase-b-rls-snapshot.sh > docs/security/pg-policies-snapshot.md
> ```
>
> CI drift guard regenerates this against a fresh Testcontainers Postgres
> on every PR touching migrations; diff-vs-committed fails the build.

## Expected shape after V67–V72 apply

### Section 1 — pg_policies (public schema)

Expected ≈22 rows across 7 tables:

| Table | Policy names (expected) |
|-------|--------------------------|
| `audit_events` | `tenant_isolation_audit_events` (FOR ALL) |
| `hmis_audit_log` | `tenant_isolation_hmis_audit_log` (FOR ALL) |
| `hmis_outbox` | `tenant_isolation_hmis_outbox` (FOR ALL) |
| `password_reset_token` | `prt_select_all`, `prt_insert_permissive`, `prt_update_permissive`, `prt_delete_permissive`, `prt_insert_restrictive`, `prt_update_restrictive`, `prt_delete_restrictive` (7 policies) |
| `one_time_access_code` | `otac_*` matching `prt_*` shape (7 policies) |
| `tenant_key_material` | `kid_material_*` (7 policies) |
| `kid_to_tenant_key` | `kid_*` (7 policies) |

The 3 fully-scoped tables (`audit_events`, `hmis_audit_log`, `hmis_outbox`)
use a single canonical FOR ALL policy with identical USING + WITH CHECK
(`tenant_id = fabt_current_tenant_id()`).

The 4 pre-auth tables use the PERMISSIVE-SELECT + RESTRICTIVE-WRITE split
per design D45 because they're queried before TenantContext is bound.

### Section 2 — GRANTs on the 7 regulated tables

Key invariants:
- `fabt_app` on `audit_events` — MUST have SELECT + INSERT, MUST NOT have UPDATE, DELETE, TRUNCATE, REFERENCES (V70 + V72 revokes)
- `fabt_app` on `hmis_audit_log` — same
- `fabt_app` on the other 5 tables — SELECT + INSERT + UPDATE + DELETE (RLS does the tenant gating, no REVOKE needed)
- `fabt_owner` — retains all privileges on all tables (runs Flyway migrations)
- `PUBLIC` — no privileges on any of the 7 regulated tables

### Section 3 — FORCE RLS flags

All 7 regulated tables MUST show:
```
relname                | relforcerowsecurity | relrowsecurity
audit_events           | t                   | t
hmis_audit_log         | t                   | t
hmis_outbox            | t                   | t
kid_to_tenant_key      | t                   | t
one_time_access_code   | t                   | t
password_reset_token   | t                   | t
tenant_key_material    | t                   | t
```

Additionally `shelter` + descendants (V8/V13/V15/V19) will appear here with
dv_access-based policies — expected, not Phase B.

### Section 4 — SECURITY DEFINER functions (D52 — MUST be empty)

No rows expected. Any row here means a migration introduced a SECURITY
DEFINER function without following the D52 governance process.

### Section 5 — LEAKPROOF functions

Expected exactly one row:

```
schema | name                   | proleakproof | provolatile | proparallel
public | fabt_current_tenant_id | t            | s           | s
```

Any other LEAKPROOF function in `public` requires explicit review — LEAKPROOF
is an operator-asserted trust marker, not an automatic property. A future
CREATE OR REPLACE of `fabt_current_tenant_id` that drops the CASE-guarded
regex would still appear here with `proleakproof=t` but the underlying
function could now throw — making the flag a lie.

## Operator procedure — regenerate before v0.43 tag

1. Apply V67–V72 against a staging DB or fresh Testcontainers Postgres:
   ```bash
   FLYWAY_URL=jdbc:postgresql://localhost:5432/fabt \
   FLYWAY_USER=fabt FLYWAY_PASSWORD=... \
       mvn flyway:migrate
   ```
2. Regenerate the snapshot:
   ```bash
   FABT_PG_OWNER_URL=postgresql://fabt:PASS@localhost/fabt \
       scripts/phase-b-rls-snapshot.sh > docs/security/pg-policies-snapshot.md
   ```
3. Diff against this template — any structural difference (new policy,
   missing grant, flipped FORCE flag, unexpected SECURITY DEFINER, unexpected
   LEAKPROOF) requires review.
4. Commit the regenerated snapshot alongside the v0.43 tag commit.

## CI drift check (to be added in CI config)

```yaml
- name: pg_policies drift check
  run: |
    # Apply migrations, regenerate snapshot, compare against committed.
    docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=ci postgres:16-alpine
    sleep 5
    FLYWAY_URL=jdbc:postgresql://localhost/postgres FLYWAY_USER=postgres FLYWAY_PASSWORD=ci mvn flyway:migrate
    FABT_PG_OWNER_URL=postgresql://postgres:ci@localhost/postgres scripts/phase-b-rls-snapshot.sh > /tmp/snapshot.md
    # Compare everything except the timestamp header line.
    if ! diff <(grep -v "Last regenerated" docs/security/pg-policies-snapshot.md) \
              <(grep -v "Last regenerated" /tmp/snapshot.md); then
        echo "::error::pg_policies drift detected. Regenerate docs/security/pg-policies-snapshot.md"
        exit 1
    fi
```
