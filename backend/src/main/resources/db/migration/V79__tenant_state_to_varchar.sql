-- V79 — Phase F slice F-1: convert tenant.state from native PG enum to VARCHAR + CHECK.
--
-- The V60 migration declared tenant.state as the native Postgres enum
-- `tenant_state` (ACTIVE|SUSPENDED|OFFBOARDING|ARCHIVED|DELETED). Spring Data
-- JDBC 4.x does not support native PG enum columns on the write path: its
-- default Enum-to-name() conversion sends the value as VARCHAR, and the
-- userConverters() pipeline is bypassed for types Spring considers "simple"
-- (all Java enums). See spring-projects/spring-data-relational#1689, #1697,
-- #1705, #1935 (still open, GDPR-write-path null-handling bug), #2083.
--
-- Industry consensus (Crunchy Data, Close.com) is to use VARCHAR + CHECK for
-- low-cardinality state columns: value-set evolution is non-blocking (NOT
-- VALID + VALIDATE allows online constraint swaps), DROP VALUE is trivial
-- (PG enums do not support DROP VALUE — removing a deprecated state would
-- require a full table rewrite under ACCESS EXCLUSIVE). Matches the rest of
-- the schema (reservation.status, shelter.deactivation_reason, etc.).
--
-- Lock shape (be honest about it): ALTER COLUMN ... TYPE ... USING is a
-- table rewrite and takes ACCESS EXCLUSIVE on `tenant` for the duration of
-- the rewrite. ADD CONSTRAINT NOT VALID takes SHARE UPDATE EXCLUSIVE (fast);
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE while it scans. DROP TYPE
-- is metadata-only. With 3 live rows on prod the total window is sub-millisecond;
-- on a hypothetical million-row future tenant table this would be the operator's
-- concern and would require a different pattern (e.g. add new column, backfill,
-- swap, drop). Live rows on prod all carry state='ACTIVE' which is in the
-- allowed set — no data change.

-- Step 1. Drop the enum-typed default so the ALTER TYPE doesn't trip over
-- 'ACTIVE'::tenant_state not being castable to the new VARCHAR type's default
-- context. We'll restore a VARCHAR default after the type change.
ALTER TABLE tenant
    ALTER COLUMN state DROP DEFAULT;

-- Step 2. Change the column type. The USING clause is the native enum->text
-- cast, which is always valid for Postgres enums.
ALTER TABLE tenant
    ALTER COLUMN state TYPE VARCHAR(32) USING state::text;

-- Step 3. Restore the default as a VARCHAR literal so new rows without an
-- explicit state continue to land as 'ACTIVE' (matches the prior enum default).
ALTER TABLE tenant
    ALTER COLUMN state SET DEFAULT 'ACTIVE';

-- Step 4. Re-assert value integrity at the DB layer. NOT VALID + VALIDATE
-- keeps the table writable during the constraint scan (all live rows are
-- 'ACTIVE', so validation is instant in practice).
ALTER TABLE tenant
    ADD CONSTRAINT tenant_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'ARCHIVED', 'DELETED'))
    NOT VALID;

ALTER TABLE tenant VALIDATE CONSTRAINT tenant_state_check;

-- Step 5. Drop the enum type. CASCADE is required because Postgres auto-
-- creates the array type `tenant_state[]` alongside the base enum — the
-- array type is not referenced anywhere in the schema but blocks a plain
-- DROP TYPE on its parent. CASCADE here drops only the array-type
-- companion; verified via:
--   SELECT pg_describe_object(classid, objid, objsubid)
--   FROM pg_depend WHERE refobjid = 'tenant_state'::regtype;
-- which, post-Step-2, returns only the array-type dependent.
DROP TYPE tenant_state CASCADE;
