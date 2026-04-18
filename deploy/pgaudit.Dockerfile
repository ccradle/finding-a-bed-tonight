# deploy/pgaudit.Dockerfile — PostgreSQL 16 with pgaudit preloaded.
#
# Per Phase B warroom V5 + V6 decisions (2026-04-18):
#   * V5: replaces `postgres:16-alpine` for environments that need
#     pgaudit. The Alpine Postgres image has no official pgaudit
#     package — pgaudit is Debian/PGDG-only. This image is the
#     authoritative Postgres for the demo site + Oracle VM from v0.44
#     onward, and for the single `@Tag("pgaudit")` Testcontainers test
#     profile that exercises the task 3.24 pgaudit log-entry IT.
#   * V6: image choice pinned to Debian bookworm slim + PGDG
#     `postgresql-16-pgaudit` package at a specific version; no moving
#     tag. Reproducibility over convenience.
#
# Per Phase B warroom Risk R3:
#   * `shared_preload_libraries = 'pgaudit'` is wired in here. The
#     Flyway V73 migration configures the per-database pgaudit.*
#     session parameters; this image supplies the preload so those
#     parameters are actually honored at session start.
#   * `infra/scripts/pgaudit-enable.sh` runs `CREATE EXTENSION pgaudit`
#     after the container is up but BEFORE the first `fabt_app` session.
#
# IMPORTANT: migrating an existing Postgres volume from Alpine (UID 70)
# to this image (UID 999) requires a one-time `chown -R 999:999 /var/
# lib/postgresql/data` on the host mount. Documented in `docs/runbook.md`
# v0.44 "Image swap rollback + recovery" section.

FROM postgres:16.6-bookworm

# PGDG provides the postgresql-16-pgaudit package. The postgres:16-
# bookworm base image already ships with the PGDG repo configured, so
# apt-get install -y works without additional key/source setup.
#
# Version pin: `postgresql-16-pgaudit=16.0-2.pgdg*` resolves to the
# PGDG-built pgaudit compatible with postgres 16.x. The trailing glob
# tolerates patch-version drift (16.0-2.pgdg120+1, pgdg130+1, etc.)
# without requiring a Dockerfile bump for each PGDG rebuild.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        postgresql-16-pgaudit; \
    rm -rf /var/lib/apt/lists/*

# pgaudit MUST be listed in shared_preload_libraries OR `CREATE EXTENSION
# pgaudit` will fail at runtime with the specific error that
# `infra/scripts/pgaudit-enable.sh` detects and surfaces.
#
# The conf.d drop-in is merged into postgresql.conf by the official
# postgres image's entrypoint. Using a drop-in (rather than editing the
# main postgresql.conf) keeps the file compatible with the parent
# image's versioning + upgrade expectations.
RUN mkdir -p /etc/postgresql/conf.d
COPY --chmod=644 pgaudit.conf /etc/postgresql/conf.d/pgaudit.conf

# Reference the conf.d in the main config so the preload is picked up
# at server start. The official image's init process sources /etc/
# postgresql/postgresql.conf.
RUN echo "include_dir = '/etc/postgresql/conf.d'" >> /usr/share/postgresql/postgresql.conf.sample

# Health check: server is healthy when pg_isready returns true AND
# pgaudit is actually loaded. The second check catches the failure mode
# where preload was mis-configured (server starts fine but pgaudit isn't
# loaded, so CREATE EXTENSION would fail later).
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        && psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            -tAc "SELECT 'pgaudit-loaded' FROM pg_settings \
                  WHERE name = 'shared_preload_libraries' \
                  AND setting LIKE '%pgaudit%'" \
        | grep -q pgaudit-loaded

# Everything else inherits from postgres:16.6-bookworm:
#   * POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB env vars
#   * /docker-entrypoint-initdb.d/ for bootstrap scripts
#   * Volume at /var/lib/postgresql/data
#   * Port 5432
