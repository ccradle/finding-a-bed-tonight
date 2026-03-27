-- Spring Batch 6.0 schema migration (from spring-batch-core 6.0.3 migration-postgresql.sql)
-- Renames BATCH_JOB_SEQ to BATCH_JOB_INSTANCE_SEQ to match updated Batch 6.0 expectations.
ALTER SEQUENCE BATCH_JOB_SEQ RENAME TO BATCH_JOB_INSTANCE_SEQ;
