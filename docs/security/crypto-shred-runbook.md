# Crypto-shred operational runbook

**Status:** active — introduced v0.51.0
**Feature doc:** `openspec/changes/multi-tenant-production-readiness/design-f6-real-cryptoshred.md`
**First version that enables hardDelete in prod:** not yet; capability ships in v0.51.0 but no prod shred scheduled

---

## What crypto-shred means on FABT

From v0.51.0 onward, `TenantLifecycleService.hardDelete(tenantId, actor, reason)` removes a tenant's per-tenant wrapped DEK rows (`tenant_dek`) via an `ON DELETE CASCADE` chain fired by a single `DELETE FROM tenant`. Post-commit, the wrapped data-encryption keys for that tenant no longer exist in the live database or in any application cache.

This is **designed to support** NIST SP 800-88 Rev 2 §2.5 "Cryptographic Erase" as the destruction mechanism for the operational copy of the data. It is **not** a compliance certification. Pre-shred backup copies, PITR retention windows, and the master KEK continue to exist per their own retention policies — see §"Backup-hygiene scope" below.

**Verifiable property:** the `CryptoShredGapIntegrationTest` anchor test (shipped in v0.51.0) asserts that an adversary with the master KEK and a pre-shred ciphertext cannot recover plaintext after the shred completes. Under the v0.51 `tenant_dek` design the data-encryption DEK is random (not HKDF-derivable), so raw-HKDF recomputation by an attacker yields a key that does not decrypt the ciphertext.

---

## Preconditions for `hardDelete`

`hardDelete` will raise `IllegalStateException` or `IllegalStateTransitionException` unless all four preconditions hold:

1. **Tenant exists.** `findById` returns the row.
2. **State is ARCHIVED.** §D8 FSM allows only `ARCHIVED → DELETED`. Other states (`ACTIVE`, `SUSPENDED`, `OFFBOARDING`) reject with `IllegalStateTransitionException` and emit a `TENANT_HARD_DELETE_REJECTED` attempt-audit.
3. **`archived_at` is non-null.** V81 schema contract — if someone manually flipped `state='ARCHIVED'` via SQL without stamping `archived_at`, the service refuses to proceed (data-integrity guard).
4. **Retention window has elapsed.** `archived_at + fabt.tenant.hard-delete.retention-days <= NOW()`. Prod default is 30 days (GDPR Art. 17 industry practice). Test harnesses override to 0 via `@TestPropertySource`.

---

## How to shred a tenant (break-glass procedure)

**Important:** v0.51.0 does NOT expose a public admin API or CLI for `hardDelete`. The method exists as a service-layer entry point; a CLI wrapper lands in a later release. For the first prod shred (if required before the CLI lands), use the Spring Boot actuator or a purpose-built one-shot migration. This section documents the operator-side procedure; run it through the warroom before executing.

### Pre-shred operator checklist

```text
☐ Tenant in state ARCHIVED?
☐ archived_at stamped > 30 days ago?
☐ pg_dump backup taken and filed in ~/fabt-backups/ with retention tag
☐ Asheville / legal-team sign-off recorded in audit_events as
  TENANT_HARD_DELETE_APPROVED (manual INSERT; platform_admin actor)
☐ Customer (if applicable) notified per data-processing agreement
☐ Prometheus alert rules for this tenant reviewed and silenced for 24h
  (avoid a flurry of "tenant disappeared" false positives)
```

### Shred execution (once the preconditions hold)

The `TenantLifecycleService.hardDelete` path is `@Transactional`:

1. FSM + retention gates verified.
2. `tenant_audit_chain_head.last_hash` captured in-memory BEFORE the CASCADE destroys it.
3. `fabt.shred_in_progress` GUC bound to the tenant UUID (V82 trigger-guard precondition).
4. `DELETE FROM tenant WHERE id = ?` — 22-FK CASCADE chain fires.
5. After-commit listener evicts `TenantDekService` + `TenantStateGuard` caches and writes a platform-owned `TENANT_HARD_DELETED` tombstone row to `audit_events`.

On rollback (trigger guard raises, FK violates, tx poisoned), the tenant row still exists and no claim-of-deletion is persisted.

---

## Post-shred verification — the operator query

Run ALL of these. Any non-zero row where zero is expected, or missing row where a row is expected, means the shred is incomplete and requires investigation. Warroom pass-3 Riley requirement — mandatory before declaring a shred complete.

> `psql` runs on the Oracle VM against the prod DB: `docker exec -it finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt`.

### 1. Tenant row is gone

```sql
SELECT COUNT(*) FROM tenant WHERE id = '<deleted-tenant-uuid>';
-- expect: 0
```

### 2. All per-tenant child tables are empty for this tenant

The `TenantChildCascadeAuditTest` allowlist enumerates the 22 CASCADE-bound tables. Repeat for each (or run the scripted version below):

```sql
WITH expected(table_name) AS (VALUES
    ('app_user'), ('api_key'), ('shelter'), ('import_log'),
    ('tenant_oauth2_provider'), ('subscription'), ('bed_availability'),
    ('reservation'), ('surge_event'), ('referral_token'),
    ('hmis_outbox'), ('hmis_audit_log'), ('bed_search_log'),
    ('daily_utilization_summary'), ('one_time_access_code'),
    ('notification'), ('password_reset_token'), ('escalation_policy'),
    ('tenant_key_material'), ('kid_to_tenant_key'),
    ('tenant_audit_chain_head'), ('tenant_dek')
)
SELECT e.table_name,
       (SELECT COUNT(*) FROM app_user WHERE tenant_id = '<uuid>') AS cnt
  FROM expected e WHERE e.table_name = 'app_user'
UNION ALL
-- Repeat per table — or automate with a DO block.
SELECT 'tenant_dek' AS table_name,
       (SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = '<uuid>');
-- expect: every `cnt` column = 0
```

**Fast version (single query):**

```sql
SELECT 'tenant_dek'              t, COUNT(*) n FROM tenant_dek              WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'tenant_key_material'     t, COUNT(*) n FROM tenant_key_material     WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'kid_to_tenant_key'       t, COUNT(*) n FROM kid_to_tenant_key       WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'tenant_audit_chain_head' t, COUNT(*) n FROM tenant_audit_chain_head WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'app_user'                t, COUNT(*) n FROM app_user                WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'shelter'                 t, COUNT(*) n FROM shelter                 WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'subscription'            t, COUNT(*) n FROM subscription            WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'tenant_oauth2_provider'  t, COUNT(*) n FROM tenant_oauth2_provider  WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'reservation'             t, COUNT(*) n FROM reservation             WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'referral_token'          t, COUNT(*) n FROM referral_token          WHERE tenant_id = '<uuid>' UNION ALL
SELECT 'notification'            t, COUNT(*) n FROM notification            WHERE tenant_id = '<uuid>';
-- expect: every n = 0
```

### 3. The platform-owned tombstone row is present

```sql
SELECT id, action, tenant_id,
       details ->> 'deleted_tenant_id'        AS deleted_tenant_id,
       details ->> 'actor_user_id'            AS actor,
       details ->> 'deleted_at'               AS deleted_at,
       details ->> 'previous_state'           AS previous_state,
       details ->> 'last_audit_chain_hash'    AS chain_hash
  FROM audit_events
 WHERE action = 'TENANT_HARD_DELETED'
   AND details ->> 'deleted_tenant_id' = '<uuid>'
 ORDER BY timestamp DESC
 LIMIT 1;
-- expect: exactly one row, tenant_id = platform SYSTEM_TENANT_ID
--         (00000000-0000-0000-0000-000000000001), chain_hash non-null
```

> The tombstone is written via `DetachedAuditPersister` (REQUIRES_NEW) from the after-commit hook. If the main tx commits but the pod crashes before the hook fires, the tenant is shredded but the tombstone is missing — see §"Known edge cases" below.

### 4. `audit_events` rows for the deleted tenant are preserved (FK-less per V57)

```sql
SELECT COUNT(*)
  FROM audit_events
 WHERE tenant_id = '<uuid>';
-- expect: > 0 (forensic trail survives shred per Q-F6-5)
--         These rows are now "orphaned" — their tenant_id points to a
--         no-longer-existing tenant UUID. This is intentional.
```

### 5. No orphan envelope bytes in the 4 encrypted columns

If any row in the 4 V83-covered columns still carries a kid that pointed at this tenant's `tenant_dek` row, that row is now undecryptable. Shouldn't happen (CASCADE destroys the owning row too), but worth confirming.

```sql
-- For each encrypted column — app_user, subscription,
-- tenant_oauth2_provider, tenant.config hmis_vendors — count rows
-- referring to the deleted tenant.
SELECT 'app_user.totp'                   t, COUNT(*) FROM app_user                WHERE tenant_id = '<uuid>' AND totp_secret_encrypted IS NOT NULL UNION ALL
SELECT 'subscription.callback'           t, COUNT(*) FROM subscription            WHERE tenant_id = '<uuid>' AND callback_secret_hash IS NOT NULL UNION ALL
SELECT 'tenant_oauth2_provider.client'   t, COUNT(*) FROM tenant_oauth2_provider  WHERE tenant_id = '<uuid>' AND client_secret_encrypted IS NOT NULL;
-- expect: every COUNT = 0 (CASCADE destroyed these rows)
```

---

## Backup-hygiene scope

**What crypto-shred destroys:** operational data in the live Postgres database. After the shred commits, the tenant's wrapped DEKs do not exist in `tenant_dek` or in the app's in-JVM caches.

**What crypto-shred does NOT destroy:**

- Pre-shred `pg_dump` backups in `~/fabt-backups/`. These retain the `tenant_dek` row as it existed at dump time. An adversary with the backup + master KEK could unwrap the DEK and decrypt ciphertext.
- Postgres PITR WAL segments (if enabled). A PITR restore into a pre-shred timestamp resurrects the deleted tenant's rows. **Document this as a retention-policy decision, not a crypto property.**
- The master KEK (`FABT_ENCRYPTION_KEY` in `.env.prod` on the VM). It is required to unwrap any DEK — including the deleted one, if the backup contains it. Master KEK rotation is a separate operational topic (Phase L).
- Grafana dashboards, Prometheus metrics retention (`prometheus_tsdb_retention_time`), log aggregator retention — these may reference the tenant ID by string.

**What the operator must do alongside a prod shred:**

1. **Record the retention window** for any pre-shred backups in a registered backup-log row. Industry practice for NIST SP 800-88 Cryptographic Erase cites the operational data as the erasure target; backup disposition is a separate policy.
2. **Rotate the master KEK** if the ARCHIVED→DELETED boundary is tied to a compliance event that demands this. v0.51.0 does NOT rotate the master KEK on hardDelete — that rotation is manual and Phase L scope.
3. **Review backup sharing contracts** (if any) to ensure the backup retention policy is documented and agreed with the subject.
4. **Confirm pgdata encryption-at-rest posture.** The Oracle VM's pgdata is on a bind-mounted volume. Whether that volume is encrypted-at-rest is an infra-level policy; `FABT_ENCRYPTION_KEY` does NOT encrypt pgdata bytes on disk. The wrapping-key + wrapped-DEK + shred model relies on the operator not leaking both the env var and a backup to the same adversary.

---

## Known edge cases

### Pod crash between main-commit and tombstone-write

`hardDelete` commits the DELETE + CASCADE in the main tx. The `TENANT_HARD_DELETED` tombstone lands via `DetachedAuditPersister` (REQUIRES_NEW) inside the after-commit hook. If the pod crashes between the main commit and the hook firing, the shred is complete but the tombstone is missing.

- **Detection:** monthly reconciliation query — join `tenant` against the last-90d `TENANT_HARD_DELETED` audit view, flag any shredded tenant without a tombstone:

  ```sql
  -- Tenants known to be shredded (in deploy-log-tracked list) but missing tombstone:
  SELECT '<uuid>' AS maybe_missing
  WHERE NOT EXISTS (
      SELECT 1 FROM audit_events
       WHERE action = 'TENANT_HARD_DELETED'
         AND details ->> 'deleted_tenant_id' = '<uuid>'
  );
  ```

- **Recovery:** the operator manually INSERTs a `TENANT_HARD_DELETED` row into `audit_events` citing the incident in the details JSONB (actor = `PLATFORM_ADMIN_MANUAL`, reason = `post-crash tombstone recovery`, event_timestamp = approximate DELETE time).

### V84 rollback

V84 flips 18 child-FKs to `ON DELETE CASCADE`. If a future change wants to revert (e.g., the CASCADE causes an unexpected production issue), the rollback is a forward-migration that runs the inverse `DROP/ADD CONSTRAINT` without the `ON DELETE CASCADE` clause. Template:

```sql
-- V85__tenant_fk_cascade_rollback.sql (DO NOT COMMIT UNLESS EXECUTING ROLLBACK)
DO $$
DECLARE
    target_tables text[] := ARRAY['app_user', 'api_key', /* ... full list from V84 ... */ ];
    tbl text; cname text;
BEGIN
    FOREACH tbl IN ARRAY target_tables LOOP
        SELECT conname INTO cname FROM pg_catalog.pg_constraint
         WHERE contype = 'f'
           AND conrelid = format('public.%I', tbl)::regclass
           AND confrelid = 'public.tenant'::regclass;
        IF cname IS NULL THEN RAISE EXCEPTION 'V85: no FK found on %', tbl; END IF;
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I', tbl, cname);
        EXECUTE format(
            'ALTER TABLE public.%I '
            'ADD CONSTRAINT %I FOREIGN KEY (tenant_id) '
            'REFERENCES public.tenant(id) NOT VALID',  -- no ON DELETE CASCADE
            tbl, cname);
        EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I', tbl, cname);
    END LOOP;
END;
$$;
```

Warroom must sign off on a CASCADE rollback; after V85 lands, `hardDelete` will fail on the first non-cascaded FK and no further shred is possible until V86 reinstates CASCADE.

### V82/V83 are also irreversible

There is no automated rollback for V82 (schema) or V83 (re-encrypt). Rollback requires `pg_restore` from the pre-deploy `pg_dump`. See the per-release deploy runbook for the `pg_dump` filename.

---

## References

- Design doc: `openspec/changes/multi-tenant-production-readiness/design-f6-real-cryptoshred.md`
- TDD anchor: `backend/src/test/java/org/fabt/shared/security/CryptoShredGapIntegrationTest.java`
- Service entry: `backend/src/main/java/org/fabt/tenant/service/TenantLifecycleService.java` (`hardDelete` method)
- Drift guard: `backend/src/test/java/org/fabt/architecture/TenantChildCascadeAuditTest.java`
- ArchUnit rules: `backend/src/test/java/org/fabt/architecture/CryptoShredArchitectureTest.java` (Family F 7.8h + 7.8j)
- NIST SP 800-88 Rev 2 §2.5 Cryptographic Erase (Sept 2025)
- RFC 5649 AES Key Wrap with Padding
- EDPB Guidelines 02/2025 on Art. 17 GDPR erasure
