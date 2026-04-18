# Logical replication posture — v1

**Status.** v1 stance, committed at Phase B close-out (2026-04-18).
**Task.** `openspec/changes/multi-tenant-production-readiness` task 3.20.
**Owner.** Phase B warroom (Casey Drummond sign-off path).

## Stance

FABT does **not** use PostgreSQL logical replication in v1. There is no
publisher slot, no subscriber, and no external replica consuming a
logical WAL stream. All read replicas (if any exist in future regulated-
tier deploys) use physical streaming replication.

## Why this matters for tenant isolation

Logical replication sends row-level changes over a protocol that
**bypasses row security policies on the publisher side by default**.
Per the PostgreSQL 16 docs (`ALTER PUBLICATION ... WITH (publish_via_
partition_root, …)`; row-security caveat in the logical-replication
chapter): a publication using the default settings will emit *every
committed row* in a tracked table regardless of the subscriber's
session context. If we enabled a publication on `audit_events` or
`hmis_audit_log`, a subscriber with its own tenant context could
receive rows belonging to tenants it has no right to see.

Phase B's `FORCE ROW LEVEL SECURITY` on the seven regulated tables is
effective only for direct SQL queries through `fabt_app`. It does NOT
constrain logical-replication output. Enabling a logical publication on
any regulated table would create a silent cross-tenant data path that
our application-layer controls cannot see.

## What we do instead

### For per-tenant data export (e.g., CoC ships DV referrals to their own HMIS)

Use `pg_dump --where='tenant_id = :tid'` with an explicit per-tenant
predicate, applied manually on a one-shot basis by an operator with
Postgres superuser credentials. The export must strip RLS policies
(the subscriber's DB schema should not carry our policies verbatim;
their policies are their own design decision):

```bash
pg_dump \
    --host <host> --user fabt --no-owner --no-privileges \
    --where="tenant_id = '<tenantId>'" \
    --table=audit_events --table=hmis_audit_log \
    --file=tenant-<tenantId>-export.sql \
    fabt
# Strip RLS before shipping (do NOT run on our side — on the recipient side):
#   - DROP POLICY statements in the dump
#   - GRANT / ALTER TABLE ... ENABLE ROW LEVEL SECURITY statements
```

The superuser credential requirement is deliberate — we don't want
this happening automatically from an application pathway where tenant
binding could drift.

### For demo/staging seed data

We build seed SQL by hand (see `~/fabt-secrets/.env.prod` +
docker-entrypoint-initdb scripts). No replication; no dump-restore of
production data into demo. A seeded demo tenant's data is isolated
from prod by never-having-been-there.

### For read replicas (regulated-tier roadmap)

When a regulated-tier deploy requires read replicas (e.g., reporting
workload separation), we will use **physical streaming replication**
(`pg_basebackup` + `standby.signal`). Physical replication replays the
WAL byte-for-byte; every row-security policy, every role grant, every
pgaudit configuration is reproduced exactly on the standby. A
tenant-scoped query on the standby enforces the same RLS as on the
primary.

Physical replication has a separate concern — the standby inherits
the primary's exact superuser access. Operators connecting to the
standby must follow the same break-glass procedures as on the primary
(`platform_admin_access_log` audit, 4-eye sign-off for `SET ROLE`
escapes).

## If v2 ever needs logical replication

Before the first logical publication is created on any regulated table:

1. Convert the regulated table to row-scoped publishing via
   `CREATE PUBLICATION ... FOR TABLE foo WHERE (tenant_id = current_setting('app.replica_tenant_id'))`
   — requires PostgreSQL 15+ (we are 16) and a tenant-context binding
   on the replication connection.
2. Document the publication + subscription in a new Phase-X design doc
   + `docs/security/compliance-posture-matrix.md` entry (what the
   publication emits, what it does NOT).
3. Add a Prometheus metric for publication row-emission volume per
   tenant — a spike that doesn't match known backfills is evidence of
   a subscriber leak.
4. CODEOWNERS review: security (Marcus Webb) + privacy (Elena) +
   compliance (Casey).

None of this exists today. The current stance is "no logical
replication, full stop." If you find yourself creating a `CREATE
PUBLICATION` statement, stop and convert to a v2 design proposal.

## Related

- `openspec/changes/multi-tenant-production-readiness/design-b-rls-hardening.md` — Phase B decisions including logical-replication-not-supported
- `docs/security/compliance-posture-matrix.md` — audit-row = committed-event contract; FORCE RLS control description
- `docs/security/phase-b-silent-audit-write-failures-runbook.md` — operator runbook for Phase B-related incidents
- `docs/security/pg-policies-snapshot.md` — current RLS policy shapes (snapshot artifact)
