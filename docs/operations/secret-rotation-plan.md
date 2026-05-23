# Secret Rotation Plan (Draft v3.2, 2026-05-22)

**Status:** planning — not yet a policy. Author + project lead must agree on cadences before this becomes operational.

**Scope:** every secret material reachable from the FABT prod VM, plus the in-app rotation surfaces (API keys, platform passwords). Tenant data-encryption keys (`tenant_dek`) are out of scope here — they're already managed by the in-app crypto-shred lifecycle.

**Goal:** turn "we rotated SMTP once after a leak" into a documented cadence with named owners, per-secret runbooks, and **a rehearsal gate that proves each runbook works before it touches prod**.

**Ground-truthed:** 2026-05-22 via three SSH passes against the prod VM. All file paths, mtime dates, container ages, env-var inventories, `platform_key_material` + `tenant_key_material` schemas, and row counts in this document are observed, not assumed. See Appendix A for provenance.

**Revision history:** v1 (2026-05-21, code-grounded only), v2 (2026-05-22, VM ground-truth + rehearsal gate), v3 (2026-05-22, warroom round 1 BLOCKER+HIGH fixes — 3 BLOCKERs + 14 HIGHs), v3.1 (2026-05-22, warroom round 2 HIGH fixes — 2 HIGHs applied, 8 MEDIUMs captured in §10), **v3.2 (2026-05-22, Marcus security-persona pass — 3 CRITICAL + 4 SIGNIFICANT control gaps closed; see §11).**

---

## 0. Core principle — rehearsal is the gate

The FABT rehearsal harness (`make rehearse-deploy`, see [`scripts/deploy-rehearsal.sh`](../../scripts/deploy-rehearsal.sh)) is a full prod-mirror compose stack with real Postgres, real Flyway, real backend container, real alertmanager, plus a Mailpit SMTP stub and ntfy stub. It runs in ~10 min, isolated via `COMPOSE_PROJECT_NAME=fabt-rehearsal` and offset ports.

The rehearsal already catches ~80% of the v0.49-class deploy bugs (per the script's own header comment): env-var trailing-space, container UID file-perm, template-engine drift, service-recreate matrix, bind-mount inode pitfall, wrong actuator URL, plus the v0.57.0 pom-version mismatch and env-var-not-reaching-container classes. **The same harness can prove a rotation runbook is correct before it touches prod.**

**Policy:**

> Every secret-rotation runbook MUST be exercised end-to-end in `make rehearse-deploy` *before* being authorized for prod execution. **The green rehearsal run MUST be no more than 72 hours old** at the moment prod rotation begins — matching the `deploy/release-gate-pins.txt` precedent for deploy gates. A prod rotation without a fresh-enough rehearsal-green run is a process violation. The single exception is an active-incident rotation (confirmed leak), which may bypass the rehearsal gate but **MUST** be (a) recorded as a `bypass=true` entry in the rotation ledger (see §8 Q4) and (b) followed by a same-week rehearsal pass that exercises the procedure used.

This costs ~10 min per rotation (rehearsal wall-clock) and pays for itself the first time a missed step would have broken prod. It is the natural extension of the gate `feedback_rehearsal_must_test_new_env_vars` established for env-var deploys (v0.57.0 abort lesson).

**Bypass-clause hygiene (Marcus v3.2):** any incident-bypass rotation MUST destroy the `.pre-rotation` backup file it created within 72 hours of confirmed smoke-clean — same window as the rehearsal-freshness clause. Otherwise the bypass leaves a cleartext copy of the credential the rotation was designed to retire. The ledger entry MUST record both the rotation timestamp AND the backup-destruction timestamp; the second timestamp is mandatory before the bypass entry is considered closed.

**What rotations can be rehearsed** (see §6 for drill specs):

| Tier | Secrets | Rehearsable? | Drill location |
|---|---|---|---|
| Periodic infra | DB×2 passwords, SMTP password, encryption key | **Yes — fully** | New Step 8.8 / 8.9 (Step 8.10 stays TBD until Tier 3 design lands) |
| Code-supported rotation | JWT secret (per-tenant kid-rotation) | **Yes — and highest-value** because never end-to-end exercised | New Step 8.11 |
| Tooling-deferred | Platform JWT signing key | When rotation-mint tooling ships, yes (schema already has `kid`) | (Step 8.12, ships with the tooling) |
| External SaaS | OCI svc principal, Cloudflare token, GH PAT | **No — managed in vendor consoles** | n/a |

---

## 1. Inventory (ground-truthed 2026-05-22)

The prod VM stores secrets in four locations:

**Location A — `~/fabt-secrets/.env.prod`** (mtime 2026-05-05; 20 variable names confirmed via `awk -F= '{print $1}'` — values not printed). Contains:
- `FABT_ALERT_*` (9 vars: SMTP host/port/user/password/TLS, email-from/to, ntfy URL/topic)
- `FABT_ENCRYPTION_KEY` (single master KEK — see note below)
- `FABT_OCI_AUDIT_ANCHOR_*` (9 vars: tenancy + user + compartment OCIDs, region, namespace, bucket, fingerprint, private-key path, enabled flag)
- `FABT_PLATFORM_CONTACT_EMAIL` — note this is a real human-facing address; survivors and the public may write to it, so changing it has user-visible implications beyond credential rotation.

**Location B — `~/fabt-secrets/docker-compose.prod.yml`** (mtime 2026-05-05; YAML key + `${VAR}`-reference names confirmed; values not printed). References (via `${VAR}`):
- `FABT_DB_*_PASSWORD` (app + owner), `FABT_DB_*_USER`, `FABT_DB_URL`
- `FABT_JWT_SECRET`
- `FABT_ENCRYPTION_KEY` (also surfaced here as backend env)
- `POSTGRES_PASSWORD`, `POSTGRES_USER`, `POSTGRES_DB`
- `GF_SECURITY_ADMIN_PASSWORD` (Grafana admin)
- Standard Spring / SpringDoc / OTel tracing config

These are `${VAR}` references — the actual values come from the operator's shell environment at `docker compose up` time. The values do not appear in any file owned by `ubuntu` (verified via the awk-only scan). **This is an audit gap** (§8 Q5): there's no sourced env file owned `ubuntu:ubuntu 600` capturing these; values live in shell history.

**Location C — Postgres tables (live, 2026-05-22 observation):**

| Table | Purpose | Schema | State |
|---|---|---|---|
| `tenant_key_material` | Per-tenant JWT signing key material | `(tenant_id, generation, created_at, rotated_at, active)` PK on `(tenant_id, generation)`, partial unique `(tenant_id) WHERE active=true`, check constraint `active=true ↔ rotated_at IS NULL`, ON DELETE CASCADE from tenant. RLS-protected. | **3 total / 3 active** rows (one per dev-coc / dev-coc-east / dev-coc-west tenant; all generation=1, none rotated) |
| `kid_to_tenant_key` | Kid registry mapping JWT kid → tenant + generation | `(kid uuid PK, tenant_id, generation, created_at)`, FK to `tenant_key_material(tenant_id, generation)`. RLS-protected. | Populated alongside tenant_key_material |
| `platform_key_material` | Platform JWT signing key material | `(id, generation, kid, key_bytes, active, created_at)`, partial unique `(true) WHERE active=true` (global single-active). **kid IS in the schema and emitted in tokens.** | 1 row, generation=1, active=true, kid populated, created **2026-04-26 22:56 UTC (27 days old). Never rotated.** |
| `jwt_revocations` | Revoked-kid cache | (schema not pulled in this pass) | (not enumerated) |
| `pg_roles.rolvaliduntil` for `fabt` + `fabt_app` | Postgres-side password expiry | n/a | **NULL** (perpetual; no Postgres-side expiry policy set) |
| `api_key.rotated_at` | In-app per-tenant API-key rotation history | n/a | Already managed via shipped `/api/v1/api-keys/{id}/rotate` |

**Location D — operator's `oci/` directory** (`~/fabt-secrets/oci/`): one file, `audit-anchor.pem` (mtime 2026-04-25; 27 days old). **Owner: `systemd-network:systemd-journal` (NOT `ubuntu`)** — this is the OCI Java SDK container's mount UID/GID. Implication for the rotation runbook: the operator (`ubuntu`) cannot directly overwrite this file; rotation needs `sudo` or a docker-stop / file-replace / docker-up sequence. The OCI runbook must capture this explicitly.

**Existing rotation primitives on the VM:**
- `~/fabt-secrets/rotate-db-password.sh` (9 lines, exists since 2026-03-30). References `docker exec`, `psql`, and `ALTER ROLE`. **This is the foundation for the DB-rotation runbook** — needs review + parameterization, not net-new authoring. §7 item 1 names the decision (keep / parameterize / replace) as a deliverable, not an implicit operator call.
- 4 timestamped `.env.prod.pre-<reason>-<timestamp>` backups already exist in `~/fabt-secrets/`. **The operator already practices "save backup before editing" hygiene; the runbooks codify it as the rollback primitive (§4 procedures).**

**Full inventory table** (combined view, prod-side):

| # | Secret | Storage location | Last touched (observed) | Rotation supported in code? | Blast radius | Tier |
|---|---|---|---|---|---|---|
| 1 | `FABT_DB_APP_PASSWORD` (`fabt_app` role) | Operator shell → `docker-compose.prod.yml` `${VAR}` | unknown (no rotation log) | Yes — `ALTER ROLE` online; existing `rotate-db-password.sh` script | Read/write all non-RLS-hidden rows for caller's tenant | 0 |
| 2 | `FABT_DB_OWNER_PASSWORD` (`fabt` role) | Operator shell → `docker-compose.prod.yml` `${VAR}` | unknown | Yes — same | Full DB; bypass RLS; runs Flyway | 0 |
| 3 | `POSTGRES_PASSWORD` (container superuser) | Operator shell → `docker-compose.prod.yml` `${VAR}` | unknown — set at first container init | Partial — requires Postgres container recreate; data persists | Full DB superuser (`postgres` role); rare to use post-init | 0 (low-frequency) |
| 4 | `FABT_JWT_SECRET` (tenant JWTs, iss=fabt) | Operator shell → `docker-compose.prod.yml` `${VAR}`. **Per-tenant key material lives in `tenant_key_material` + `kid_to_tenant_key`.** | unknown for env var / **2026-04-26 for material rows** (generation 1, all 3 tenants, none rotated) | **Yes** — schema supports kid-keyed rotation per-tenant (insert new generation row, flip prior to active=false + set rotated_at). Runtime read path via `KidRegistryService` + revoked-kid cache. **Never exercised end-to-end.** | Forge any tenant user's session | 1 |
| 5 | Platform JWT signing key (iss=fabt-platform) | DB `platform_key_material` (generation 1, kid populated, active=true, created 2026-04-26) | **2026-04-26 (27 days ago)** — never rotated | **Partial** — schema ships with `kid` + `(true) WHERE active=true` partial-unique. Tokens emit kid header. Missing: rotation MINT tooling (admin endpoint that inserts new active generation and flips prior). | Forge platform-operator session | 2 |
| 6 | `FABT_ENCRYPTION_KEY` (master KEK — *AND* TOTP-secret encryption via fallback) | `.env.prod` (single variable in prod; rehearsal env has two for parity with future split — see §8 Q3) | unknown — file last edited 2026-05-05 for `FABT_PLATFORM_CONTACT_EMAIL` addition (NOT a key change) | **No** — single-key only. `EncryptionEnvelope` v1 envelope has a 16-byte kid field reserved per `docs/FOR-DEVELOPERS.md` but **legacy envelopes have null/zero kid and need a back-fill migration before dual-key works** | Decrypt **every** tenant DEK → every encrypted PII column + every TOTP secret. **Loss = irrecoverable data loss; survivor-safety failure mode: TOTP secrets unrecoverable → MFA-locked DV operators cannot reach the system.** | 3 |
| 7 | `FABT_ALERT_SMTP_PASSWORD` | `.env.prod` | **2026-04-22 (30 days ago)** — rotated after v0.49 leak | Yes — Gmail console + `alertmanager.yml` regen + alertmanager recreate | Send/receive mail as the alert mailbox | 0 |
| 8 | `GF_SECURITY_ADMIN_PASSWORD` (Grafana admin) | Operator shell → `docker-compose.prod.yml` `${VAR}` | unknown | Yes — Grafana UI rotation + container env update + recreate | Grafana admin → view all dashboards / change config | 0 |
| 9 | OCI svc principal (`fabt-audit-anchor`) | `.env.prod` (9 vars) + key file `~/fabt-secrets/oci/audit-anchor.pem` (**owner `systemd-network:systemd-journal`, not ubuntu — needs sudo to overwrite**) | **2026-04-25 (27 days ago)** for the .pem file mtime | Yes — OCI console rotates fingerprint+key+OCID | OCI bucket write (audit anchoring fails open or false-anchors) | 0 |
| 10 | `FABT_PLATFORM_CONTACT_EMAIL` | `.env.prod` | **2026-05-05 (17 days ago)** | n/a — not a secret, contains a real human-facing address. Changing it has user-visible legal/process implications (survivors may write to it). | Public address — but `feedback_no_ip_in_repo` posture: never committed to git | n/a |

**External secrets — NOT stored on the VM:**

| # | Secret | Where it lives | Notes |
|---|---|---|---|
| 11 | Cloudflare API token | Cloudflare dashboard | Not in `.env.prod` (confirmed). Used from operator laptop only. |
| 12 | GitHub PAT (for `gh` CLI / Actions) | GitHub Actions secrets (CI) + operator laptop (`~/.config/gh/`) | Not in `.env.prod` (confirmed). |

**In-app rotation surfaces (shipped, included for completeness):**

| Surface | Rotation mechanism | Coverage |
|---|---|---|
| Per-tenant API keys | `POST /api/v1/api-keys/{id}/rotate` (24h grace; `rotated_at` column populated) | Already documented in `docs/FOR-DEVELOPERS.md` |
| Platform-operator passwords | In-UI invite + first-password flow (Slice E, v0.54) | Per-user, on-demand |
| Per-tenant `tenant_dek` | Crypto-shred via tenant FSM lifecycle | Per-tenant, on tenant decommission |

---

## 2. Risk tiers

**All Tier 0 and Tier 1 work goes through the rehearsal gate; Tier 2 and Tier 3 build the rehearsal drills as part of their tooling deliverable.**

**Tier 0 — periodic hygiene (rehearseable; rotate on calendar):**
- #1 DB app password — quarterly
- #2 DB owner password — annually (lower blast tier; requires VM access to use)
- #3 POSTGRES_PASSWORD — annually
- #7 SMTP password — quarterly (already at 30 days; next due ~2026-07-22)
- #8 Grafana admin password — quarterly
- #9 OCI svc principal — quarterly *(per OCI best practice + audit-anchor posture)*
- #11 Cloudflare token — quarterly (vendor-console only; can't rehearse)
- #12 GitHub PAT — annually (Fine-Grained PAT 1y max; vendor-console only)

**Tier 1 — rehearseable; rotate after incident OR annually with planning:**
- #4 `FABT_JWT_SECRET` + per-tenant key material — annually. Schema supports kid rotation but never exercised end-to-end. Rehearsal drill (§6.4) retires the "tooling exists but never run" risk *before* first prod rotation.

**Tier 2 — needs MINT tooling; rehearsal drill ships with the tooling:**
- #5 Platform JWT signing key — schema is ready (kid emitted in tokens, partial-unique-on-active enforces single live generation). Need an admin endpoint to mint a new active row + flip prior + enforce grace window. Drill becomes Step 8.12.

**Tier 3 — needs design work AND legacy-envelope kid back-fill; rehearsal is the natural proving ground when design ships:**
- #6 `FABT_ENCRYPTION_KEY` (master KEK + TOTP via fallback) — needs dual-key-accept envelope. `EncryptionEnvelope` already reserves a kid slot per `docs/FOR-DEVELOPERS.md`, but **existing v1 envelopes in `tenant_dek` and any other encrypted column have null/zero kid and must be back-filled (re-wrapped) before dual-key reads can work.** Plan: pilot the dual-key design on TOTP-encryption-only path (lower blast radius) before applying to the master KEK.

---

## 3. Recommended cadences

| Tier | Secret | Cadence | Owner | Detection / how operator knows to bring it forward | Audit emit on rotation |
|---|---|---|---|---|---|
| 0 | #1 DB app password | every 90 days | sole operator¹ | Operator-initiated; **post-policy: Prometheus alert on `pg_roles` password-change event** (§7 item 18) | `DB_ROLE_ROTATED` (operator, role, ts) |
| 0 | #2 DB owner password | every 365 days | sole operator¹ | Operator-initiated; same Prometheus rule as #1 | `DB_ROLE_ROTATED` |
| 0 | #3 POSTGRES_PASSWORD | every 365 days | sole operator¹ | Operator-initiated only | `POSTGRES_SUPERUSER_ROTATED` |
| 0 | #7 SMTP password | every 90 days | sole operator¹ | Operator-initiated; Gmail security-alert email triggers incident path | `SMTP_PASSWORD_ROTATED` |
| 0 | #8 Grafana admin | every 90 days | sole operator¹ | Operator-initiated only | `GRAFANA_ADMIN_ROTATED` |
| 0 | #9 OCI svc principal | every 90 days | sole operator¹ | OCI audit-log scan deferred (manual quarterly review); **post-policy: OCI list-keys assertion catches "new+old both live" partial states** (§7 item 8) | `OCI_SVC_PRINCIPAL_ROTATED` (fingerprint redacted) |
| 0 | #11 Cloudflare token | every 90 days | sole operator¹ | CF audit log scan deferred (manual quarterly review) | `CLOUDFLARE_TOKEN_ROTATED` |
| 0 | #12 GitHub PAT | every 365 days (or fine-grained 90d) | sole operator¹ | GitHub email warning on PAT expiry | `GITHUB_PAT_ROTATED` |
| 1 | #4 JWT secret + per-tenant key material | every 365 days OR on suspected forgery | sole operator¹ + dev | Forgery suspected; key-derivation downgraded; **Prometheus alert on `tenant_key_material` INSERT rate above expected cadence** (§7 item 18) | `TENANT_KEY_ROTATED` on every `active` flip in either direction (downgrade-attack detection per Marcus v3.2) |
| 2 | #5 Platform JWT key | every 365 days once tooling ships | sole operator¹ + dev | Platform-operator account compromise; same Prometheus rule on `platform_key_material` | `PLATFORM_KEY_ROTATED` on every `active` flip in either direction |
| 3 | #6 Master KEK | DO NOT rotate until dual-key-accept design ships + kid back-fill complete. Annually thereafter. | sole operator¹ + dev | KEK exposure (catastrophic) | `MASTER_KEK_ROTATED` (with envelope kid-back-fill row count) |

¹ **Owner = sole operator (project lead Corey Cradle) today. No documented fallback exists.** When a second operator joins, the dev/ops split formalizes per §8 Q1. Until then, this column is a single-point-of-failure for every rotation: operator illness, PTO, or unavailability blocks rotation. **Risk-acceptance, not policy adherence.**

**Shell-history is a credential store (Marcus v3.2, CRITICAL).** Every rotation procedure in §4 that uses `export FABT_*_PASSWORD=...` would, without mitigation, leak the new value into `~/.bash_history`. An attacker with read access to the operator laptop would have perpetual access to every rotation value ever set. Every §4 procedure now opens with `unset HISTFILE` (or equivalent) BEFORE the first `export` and ends with `history -c` AFTER smoke-gate. The "Audit emit" column above is the detection layer when this control fails; both layers are required.

**Documented exception:** any secret on this list can be rotated *immediately* outside cadence in response to a confirmed leak (the SMTP rotation 2026-04-22 set the precedent). Incident rotations bypass the rehearsal gate per §0 but require (a) ledger entry with `bypass=true` + reason + operator timestamp, (b) a same-week rehearsal follow-up.

---

## 4. Per-secret procedure sketches

These are *sketches* — each becomes a full runbook under `docs/operations/runbooks/` (using the §7 item 0 template). Each runbook MUST include: prerequisites (with glossary for `envsubst` / `amtool` / kid / RLS / bind-mount), the procedure, an explicit rollback section, and a smoke-gate definition.

### #7 SMTP password (precedent: 2026-04-22 rotation)

```
PREREQUISITES: envsubst on PATH, $EDITOR set, alertmanager.yml.tmpl present.
GLOSSARY: envsubst = GNU env-var substitution; alertmanager UID 65534 = nobody.

NEVER-PRINT INVARIANT (control, not citation): the next steps edit and re-render
files that contain the SMTP password. Do NOT `cat`, `head`, `tail`, `grep`, or
otherwise output any portion of the rendered file to your terminal at any point.
`$EDITOR` opens the file directly without piping its contents.

SHELL-HYGIENE PREAMBLE (Marcus v3.2 CRITICAL): rotation procedures touch
secrets via the operator's shell. Before any `export` or `read` of a secret
value, the operator's bash history must be silenced so the new value never
lands in ~/.bash_history. Execute these THREE lines before step 1:

  unset HISTFILE                    # disables history for this session
  set +o history                    # belt-and-suspenders for bash 5+
  trap 'history -c' EXIT            # clears in-memory history on shell exit

These three lines are part of every §4 procedure. Without them, every
`export FABT_*_PASSWORD=...` in the session leaks to disk.

1. Gmail → security → app passwords → revoke old, create new (do NOT print)
2. cp ~/fabt-secrets/.env.prod ~/fabt-secrets/.env.prod.pre-smtp-$(date +%Y%m%d-%H%M%S)
   # ⚠ This backup IS the rollback primitive — referenced by step ROLLBACK below.
   # ⚠ This backup is also a credential-bearing file with 72h destruction window
   #   per the BACKUP-DESTRUCTION step.
3. Edit .env.prod via $EDITOR (NEVER cat/grep first)
4. cd ~/finding-a-bed-tonight && envsubst < deploy/alertmanager.yml.tmpl > ~/fabt-secrets/alertmanager.yml
5. chmod 644 ~/fabt-secrets/alertmanager.yml  (alertmanager UID 65534 needs read)
6. docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml up -d --force-recreate alertmanager
7. Smoke gate: amtool alert add test → confirm receipt at alert mailbox
8. Audit: confirm old app password rejected in Gmail console
9. AUDIT EMIT: backend logs `SMTP_PASSWORD_ROTATED` event with operator + ts (per §3 audit column)
10. Ledger: append entry to rotation-ledger.json (secret=SMTP, ts, operator, rehearsal-run-id, bypass=false)
11. BACKUP-DESTRUCTION (mandatory, NLT 72h post-smoke-clean): `shred -u ~/fabt-secrets/.env.prod.pre-smtp-<ts>`
    then append destruction timestamp to the same ledger entry. The entry is NOT
    closed until both timestamps land. See §0 bypass-clause hygiene + §11 #1.
12. SHELL-CLEAN: `history -c` (defensive — the trap already runs on EXIT but
    explicit clear before any other operator action is the safe default).

ROLLBACK (if step 6, 7, or 8 fails):
  R1. mv ~/fabt-secrets/.env.prod.pre-smtp-<ts> ~/fabt-secrets/.env.prod
  R2. envsubst < deploy/alertmanager.yml.tmpl > ~/fabt-secrets/alertmanager.yml ; chmod 644
  R3. docker compose ... up -d --force-recreate alertmanager
  R4. Re-test with old password via amtool
  R5. File post-incident note explaining what failed BEFORE attempting rotation again
  R6. The 72h backup-destruction step (#11) still applies on the OLD backup file —
      the rollback does NOT extend the cleartext-on-disk window.
```

### #1 / #2 DB passwords (leverage existing `rotate-db-password.sh`)

```
PREREQUISITES: openssl, docker exec, psql, pg_dump. Existing 9-line
  rotate-db-password.sh reviewed/parameterized per §7 item 1.
GLOSSARY: ALTER ROLE = Postgres role-password update; pg_hba = host-based auth
  config; SERIALIZABLE = strongest Postgres transaction isolation level.

SHELL-HYGIENE PREAMBLE (per §4 SMTP — same three lines): unset HISTFILE;
  set +o history; trap 'history -c' EXIT.

0a. PRE-ROTATION SNAPSHOT (Marcus v3.2 CRITICAL, replaces §8 Q3 deferral):
    pg_dump -h <host> -U fabt -t platform_key_material -t tenant_key_material \
      -t kid_to_tenant_key | gpg --encrypt --recipient <ops-pgp-key> \
      > ~/fabt-secrets/pre-rotation-pg_dump-$(date +%Y%m%d-%H%M%S).sql.gpg
    chmod 600 ~/fabt-secrets/pre-rotation-pg_dump-*.sql.gpg
    Retention: 30 days post-rotation. Snapshot is the rollback primitive for
    §4 #4 and §4 #5 — without it, beyond-grace rollback fails open.
1. Generate new password: NEW_PWD=$(openssl rand -base64 32)
2. cp ~/fabt-secrets/docker-compose.prod.yml ~/fabt-secrets/docker-compose.prod.yml.pre-db-$(date +%Y%m%d-%H%M%S)
3. As operator shell: export FABT_DB_APP_PASSWORD="$NEW_PWD"
   # ⚠ With HISTFILE unset and trap armed, the export does NOT touch ~/.bash_history.
4. OLD_PWD=$(awk -F= '/^FABT_DB_APP_PASSWORD/{print $2}' \
   ~/fabt-secrets/docker-compose.prod.yml.pre-db-<ts>)
   # OLD captured from the backup (M3-r2 fix), NOT from shell history.
5. bash ~/fabt-secrets/rotate-db-password.sh fabt_app  (runs ALTER ROLE inside container)
6. docker compose -f ... up -d --force-recreate backend
7. Smoke gate: /actuator/health green; one authenticated API request green
8. Audit: psql connect with OLD password rejected; NEW password accepted via the
   SAME network path the backend uses (postgres:5432 service DNS, not localhost)
9. AUDIT EMIT: backend logs `DB_ROLE_ROTATED` event (per §3 audit column)
10. Ledger entry (bypass=false, snapshot_path=<pg_dump file>)
11. BACKUP-DESTRUCTION (mandatory, NLT 72h post-smoke-clean):
      shred -u ~/fabt-secrets/docker-compose.prod.yml.pre-db-<ts>
      # pg_dump snapshot is NOT destroyed here — 30-day retention applies.
    Append destruction timestamp to ledger.
12. SHELL-CLEAN: history -c
13. unset OLD_PWD NEW_PWD  # in-memory scrub before any other operator action

ROLLBACK (within smoke-gate window):
  R1. ALTER ROLE fabt_app WITH PASSWORD '<OLD_PWD captured in step 4>'
  R2. export FABT_DB_APP_PASSWORD="$OLD_PWD"
  R3. docker compose ... up -d --force-recreate backend
  R4. /actuator/health
  R5. AUDIT EMIT: `DB_ROLE_ROTATION_ROLLED_BACK` (detection layer for forced rollback)
  R6. Backup-destruction (#11) still applies on the OLD backup at the originally-
      scheduled 72h mark; rollback does NOT extend the cleartext-on-disk window.

ROLLBACK (after smoke-gate window, beyond shell-session):
  Restore the encrypted pg_dump from step 0a; out-of-band runbook required;
  treat as incident.
```

### #4 JWT secret per-tenant kid-rotation (already supported, never exercised)

```
PREREQUISITES: psql access, knowledge of kid-keyed JWT validation.
GLOSSARY: kid = key-id JWT header field; tenant_key_material partial-unique on
  (tenant_id) WHERE active=true enforces single live generation per tenant.

PRE-ROTATION: per §4 DB step 0a, take an encrypted pg_dump of
tenant_key_material + kid_to_tenant_key + jwt_revocations BEFORE proceeding.
The Marcus v3.2 grace-window-fail-open mitigation depends on this snapshot.

For each tenant T (wrap the per-tenant flow in a SERIALIZABLE transaction —
Marcus v3.2 mitigation against paused-JVM-sees-two-actives observability gap):

  BEGIN;
  SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
  SET LOCAL fabt.current_tenant_id = '<T>';

  -- 1. Deactivate current generation
  UPDATE tenant_key_material
    SET active = false, rotated_at = clock_timestamp()
    WHERE tenant_id = '<T>' AND active = true
    RETURNING generation INTO @prev_gen;

  -- 2. Insert new generation
  INSERT INTO tenant_key_material (tenant_id, generation, created_at, active)
    VALUES ('<T>', @prev_gen + 1, clock_timestamp(), true);

  -- 3. Register new kid
  INSERT INTO kid_to_tenant_key (kid, tenant_id, generation)
    VALUES (gen_random_uuid(), '<T>', @prev_gen + 1);

  -- 4. INVALIDATE jwt_revocations cache for the deactivated kid (Marcus v3.2
  --    M4-r2 reclassified as security-adjacent: stale cache lets a leaked
  --    pre-rotation kid validate past its rotated_at).
  DELETE FROM jwt_revocations
    WHERE tenant_id = '<T>'
      AND kid IN (SELECT kid FROM kid_to_tenant_key
                  WHERE tenant_id = '<T>' AND generation = @prev_gen);

  COMMIT;

  -- 5. AUDIT EMIT (per §3 audit column): backend logs `TENANT_KEY_ROTATED`
  --    event with operator + tenant_id + prev_gen + new_gen + ts.
  --    The event MUST fire on every `active` flip in EITHER direction —
  --    including the rollback path R1 below — to detect downgrade attacks.

Caches expire normally; clients reauthenticate over 15-min TTL window.

The check constraint `active=true AND rotated_at IS NULL OR active=false AND
rotated_at IS NOT NULL` enforces shape; the partial unique index prevents two
active rows per tenant.

**Read-path correctness note (Marcus v3.2):** the JWT validation read path
(`KidRegistryService`) MUST reject a token whose kid resolves to a
`tenant_key_material` row where `active = false` AND `rotated_at < now() - grace_window`.
Validating on `active` alone permits a leaked pre-rotation token to forge
sessions perpetually after the rotation that was supposed to retire it.
Verify the read path before authorizing any prod rotation under this procedure.

ROLLBACK (within grace window, before clients have reauthenticated):
  R1. UPDATE tenant_key_material SET active=true, rotated_at=NULL WHERE generation=<old>
      -- This triggers a `TENANT_KEY_ROTATED` audit emit (downgrade-attack
      -- detection per the §3 audit column rule).
  R2. UPDATE tenant_key_material SET active=false, rotated_at=clock_timestamp() WHERE generation=<new>
      -- Also emits.
  R3. Verify partial-unique invariant still holds.

ROLLBACK (beyond grace window): restore from the §4 DB step 0a pg_dump.
Out-of-band; treat as incident.
```

### #5 Platform JWT signing key (Tier 2 — needs tooling)

- Build `POST /api/v1/platform/key-material/rotate` endpoint, `@PlatformAdminOnly`
- Calls `KeyDerivationService.derivePlatformJwtKeyBytes(new UUID)` → INSERT new row with new kid + active=true + generation=N+1 → flip previous to active=false (partial-unique prevents two active rows)
- **Transaction isolation (Marcus v3.2):** wrap INSERT-new + UPDATE-old in a single `SERIALIZABLE` transaction. The partial-unique index prevents COMMIT of two-actives but does not prevent mid-transaction observability; isolation level makes the flip atomic from the verifier's perspective.
- During grace window (1 hr default), platform JWT verifier accepts either generation by looking up `platform_key_material WHERE kid=<header.kid>` (kid is already in schema and emitted in tokens — no kid-emission pre-work needed). **Verifier MUST reject** if `active = false AND rotated_at < now() - grace_window` (same Marcus v3.2 read-path correctness rule as §4 #4).
- Audit emit: `PLATFORM_KEY_ROTATED` on every `active` flip in either direction (detection layer for downgrade attacks via direct DB write).
- Drill (§6.5): rehearsal starts at generation N (the ground-truthed live state), runs the rotation endpoint, asserts generation N+1 active, asserts old-generation tokens minted pre-rotation still validate within grace, asserts new tokens carry new kid, asserts old-generation tokens are REJECTED past `grace_window` (positive control for the read-path correctness rule).

### #6 Master KEK — dual-key-accept (Tier 3 — needs design AND back-fill)

- Two env vars: `FABT_ENCRYPTION_KEY_CURRENT`, `FABT_ENCRYPTION_KEY_PREVIOUS`
- **Prerequisite — kid back-fill migration:** existing v1 envelopes in `tenant_dek` (and any other encrypted column populated under the current single-key regime) have null/zero in their reserved kid bytes. A migration MUST iterate every encrypted row, read with the current key, re-emit the envelope with the current key's UUID populated in the kid slot. Until this back-fill runs, dual-key reads cannot determine which key encrypted a legacy row.
- Wire the 16-byte kid field in `EncryptionEnvelope` v1 (already reserved per `docs/FOR-DEVELOPERS.md`) so dual-key reads can match envelope kid against current vs previous KEK UUID.
- Read path: try current → fall back to previous if kid matches previous's UUID. Write path: always current.
- Re-wrap migration: re-emit every `tenant_dek` row under the new current key, then drop the previous key from prod env.
- **AEAD construction (Marcus v3.2 CRITICAL):** kid alone MUST NOT authorize key selection. The envelope MUST be authenticated (AES-GCM or equivalent AEAD), the MAC MUST cover the kid field as **associated data**, and the read path MUST reject the envelope if MAC verification fails under the selected key. Without this, an attacker who knows a leaked PREVIOUS key can craft an envelope with attacker-controlled kid pointing to PREVIOUS → decrypt-with-leaked-key → forged plaintext under the application's signing posture. Mitigation is baked into the cryptographic primitive (AEAD); the design must specify it before any code lands.
- **Drill (§6.6):** rehearsal sets up `tenant_dek` rows under KEK-A, performs kid back-fill, then performs full migration to KEK-B, asserts: reads work for ALL rows (back-filled AND newly-encrypted), writes use KEK-B kid, dropping KEK-A doesn't break reads of rows written under KEK-B, AND a tampered envelope with bad MAC is rejected (positive control for the AEAD assertion above).

---

## 5. Closed open questions from earlier drafts

| # | Question | Status |
|---|---|---|
| v1-Q3 | Cloudflare token + GitHub PAT in `.env.prod`? | **Answered: no.** Confirmed via `awk` variable-name scan 2026-05-22. Vendor-console / operator-laptop only. |
| v1-Q4 | OCI captured-at date in memory file — accurate? | **Partially answered.** `~/fabt-secrets/oci/audit-anchor.pem` mtime is 2026-04-25 (27 days). Memory file capture-date should be aligned (§8 Q5). |
| v1-Q5 | `FABT_PLATFORM_CONTACT_EMAIL` — move out of `.env.prod`? | **Answered: leave it.** Currently in `.env.prod`, last edited 2026-05-05. **Approval co-signed:** operator decision (sole operator); flagged in §8 for second-operator review when one joins. |
| v2-Q3 | KEK + TOTP — one key or two? | **Answered: one in prod, two in rehearsal env.** `.env.prod` has only `FABT_ENCRYPTION_KEY` — TOTP path falls back to it via `SecretEncryptionService`. Rehearsal env mirrors a *future* split (forward-compatibility) but prod is single-key. |

---

## 6. Rehearsal drill specifications

**DV-tenant exclusion (control):** rehearsal seed data (`infra/scripts/seed-data.sql`) intentionally contains NO real DV-survivor PII and NO production DV-tenant rows — only synthetic `dev-coc*` tenants. **All drills below MUST operate against `dev-coc*` tenants only. Any drill that exercises DV-flagged shelter rows or DV-coordinator user paths is a process violation that must be flagged in warroom and the rehearsal env reset.** Casey-veto.

**Rehearsal-harness trust boundary (Marcus v3.2 SIGNIFICANT):** the §0 policy makes `scripts/deploy-rehearsal.sh` authoritative for prod authorization. If the script is compromised via a malicious PR (drill that fakes "OK" silently), the gate is one-PR-deep. Mitigations:

1. **Co-sign requirement (when second operator exists):** any PR that touches `scripts/deploy-rehearsal.sh` requires approval from a second operator before merge. Captured as future §7 backlog dependency.
2. **Ledger flag (interim, single-operator):** every rehearsal-run-id in `rotation-ledger.json` MUST record the script's commit SHA at run time. If a rotation's recorded SHA differs from the SHA at the most-recent prior rotation, a manual review step is required before proceeding. This is the interim control while there is only one operator.
3. **Branch-protection requirement:** `scripts/deploy-rehearsal.sh` is added to the CODEOWNERS file (or branch-protection allow-list) such that GitHub flags any change. This is a forcing function on the PR-review surface even without a second operator.

Each drill becomes a numbered step in `scripts/deploy-rehearsal.sh`, added between the existing Step 8.5 (seed data load) and Step 9 (synthetic alert routing). Each one runs in 30-90 seconds; full rehearsal stays under 12 min.

**All drills MUST start with the rehearsal-env guard** (B3 fix):

```bash
[[ "$ENV_FILE" == *.rehearsal ]] \
  || fail "drill REFUSES to run against non-rehearsal env file: $ENV_FILE"
```

This ENV_FILE guard is a top-of-drill control, not a citation — copy/paste of the drill verbatim against `.env.prod` will exit non-zero before any `sed -i` touches the file.

**Rehearsal env reset** (M1 fix): each drill snapshots `.env.rehearsal` at start (`cp .env.rehearsal /tmp/env-pre-<drill>-<ts>`) and restores it at end. Two consecutive `make rehearse-deploy` runs MUST produce byte-identical results in the env file.

### §6.1 — Step 8.8: DB app-password rotation drill

```bash
[[ "$ENV_FILE" == *.rehearsal ]] || fail "..."
cp "$ENV_FILE" "$ENV_FILE.pre-drill-$$"
trap "mv '$ENV_FILE.pre-drill-$$' '$ENV_FILE'" EXIT

NEW_PWD=$(openssl rand -base64 32)

# 1. ALTER ROLE inside Postgres container (mirrors prod)
docker exec fabt-rehearsal-postgres psql -U fabt -d fabt -c \
  "ALTER ROLE fabt_app WITH PASSWORD '$NEW_PWD';" >/dev/null

# 2. Old password rejected — assert via BACKEND NETWORK PATH (postgres:5432
#    service DNS), not localhost-inside-container. This matches what the
#    backend container actually does per docker-compose env line 38.
#    (H6 fix.)
docker run --rm --network fabt-rehearsal_default \
  -e PGPASSWORD='<OLD>' postgres:16-alpine \
  psql -h postgres -U fabt_app -d fabt -c "SELECT 1;" 2>&1 \
  | grep -q "authentication failed" \
  || fail "BACKEND PATH: old password should be rejected after rotation"

# 3. New password works via backend path
docker run --rm --network fabt-rehearsal_default \
  -e PGPASSWORD="$NEW_PWD" postgres:16-alpine \
  psql -h postgres -U fabt_app -d fabt -c "SELECT 1;" >/dev/null \
  || fail "BACKEND PATH: new password should authenticate"

# 4. Backend env updated + recreate
sed -i "s|^FABT_DB_APP_PASSWORD=.*|FABT_DB_APP_PASSWORD=$NEW_PWD|" "$ENV_FILE"
docker compose -f docker-compose.yml -f deploy/rehearsal-prod-overlay.yml \
  -p fabt-rehearsal up -d --force-recreate backend

# 5. Backend health green within 60s
wait_for_health || fail "backend failed to come up with new password"

echo "[OK] Step 8.8: DB app-password rotation drill"
```

### §6.2 — Step 8.9: SMTP password rotation drill (Mailpit-validated)

```bash
[[ "$ENV_FILE" == *.rehearsal ]] || fail "..."
cp "$ENV_FILE" "$ENV_FILE.pre-drill-$$"
trap "mv '$ENV_FILE.pre-drill-$$' '$ENV_FILE'" EXIT

OLD_SMTP_PWD=$(awk -F= '/^FABT_ALERT_SMTP_PASSWORD/{print $2}' "$ENV_FILE")
# REHEARSAL-ONLY PATTERN: the next assignment uses a plaintext value as the
# Mailpit-auth-log-grep needle. This is intentional for a Mailpit-stubbed
# rehearsal where the value carries no real authority. DO NOT lift this
# pattern into any prod runbook — `grep -F "$REAL_SECRET"` in a prod context
# would print the secret to the operator's terminal and recent shell history,
# which violates `feedback_never_print_rendered_secrets`. Prod rotations
# verify success via the OUTCOME (alert lands, /actuator/health green, etc.),
# never by grepping for the secret value itself.
NEW_SMTP_PWD="rehearsal-rotated-$(date +%s)"

sed -i "s|^FABT_ALERT_SMTP_PASSWORD=.*|FABT_ALERT_SMTP_PASSWORD=$NEW_SMTP_PWD|" "$ENV_FILE"

# Per-var envsubst with explicit error on missing (M7 fix)
for v in FABT_ALERT_SMTP_HOST FABT_ALERT_SMTP_PORT FABT_ALERT_SMTP_USER \
         FABT_ALERT_SMTP_PASSWORD FABT_ALERT_SMTP_REQUIRE_TLS \
         FABT_ALERT_EMAIL_FROM FABT_ALERT_EMAIL_TO \
         FABT_ALERT_NTFY_URL FABT_ALERT_NTFY_TOPIC; do
  grep -q "^$v=" "$ENV_FILE" || fail "drill: env var $v missing from $ENV_FILE"
done
envsubst < deploy/alertmanager.yml.tmpl > ~/.fabt-rehearsal/alertmanager.yml
chmod 644 ~/.fabt-rehearsal/alertmanager.yml

docker compose ... up -d --force-recreate alertmanager

# Capture Mailpit message-count BEFORE firing the alert (H5 fix)
PRE_COUNT=$(curl -sS http://localhost:18025/api/v1/messages | jq '.messages | length')

amtool alert add severity=test test_alert_id=rotation-drill \
  --alertmanager.url=http://localhost:19093

# Wait up to 30s for new message to arrive
for i in {1..30}; do
  POST_COUNT=$(curl -sS http://localhost:18025/api/v1/messages | jq '.messages | length')
  [[ "$POST_COUNT" -gt "$PRE_COUNT" ]] && break
  sleep 1
done
[[ "$POST_COUNT" -gt "$PRE_COUNT" ]] \
  || fail "rotated SMTP password did not deliver: count was $PRE_COUNT, now $POST_COUNT"

# H5: assert Mailpit auth log shows the NEW password was used (not the old).
# Mailpit logs SMTP-AUTH attempts; the most recent AUTH line MUST contain
# the new-password marker (the timestamp-derived stub uniquely identifies the
# new value).
docker logs fabt-rehearsal-mailpit 2>&1 \
  | grep -F "$NEW_SMTP_PWD" \
  || fail "Mailpit auth log does NOT show the new password — drill passed on stale creds"

echo "[OK] Step 8.9: SMTP rotation drill (count + auth-log verified)"
```

### §6.3 — Step 8.10: deferred until Tier 3 design lands

The encryption-key envelope round-trip drill cannot ship until the dual-key envelope design ships (§4 #6). Placeholder kept here so the numbering aligns with §6.6 when it does land.

### §6.4 — Step 8.11: JWT-secret per-tenant kid-rotation drill (HIGHEST VALUE)

Retires the "code supports it but never exercised end-to-end" risk. Schema verified 2026-05-22 against prod: `tenant_key_material` PK `(tenant_id, generation)`, partial unique `(tenant_id) WHERE active=true`, check constraint `active ↔ rotated_at IS NULL`. RLS-protected — drill MUST set `fabt.current_tenant_id` before mutations.

```bash
[[ "$ENV_FILE" == *.rehearsal ]] || fail "..."

# 1. Pick a non-DV dev tenant (DV exclusion control)
TENANT_ID=$(docker exec fabt-rehearsal-postgres psql -U fabt -d fabt -tA -c \
  "SELECT id FROM tenant WHERE slug='dev-coc' LIMIT 1;")
[[ -n "$TENANT_ID" ]] || fail "dev-coc tenant not seeded"

# 2. Capture a token minted under current generation
OLD_TOKEN=$(curl -sS -X POST http://localhost:18080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"outreach@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  | jq -r .accessToken)
# URL-safe base64 decode with padding fix (N2)
PAD() { echo "$1" | awk '{ l=length($0); p=(4-l%4)%4; printf "%s", $0; for(i=0;i<p;i++) printf "="; }' | tr '_-' '/+'; }
OLD_KID=$(PAD "$(echo "$OLD_TOKEN" | cut -d. -f1)" | base64 -d 2>/dev/null | jq -r .kid)
[[ "$OLD_KID" != "null" && -n "$OLD_KID" ]] || fail "old token missing kid header"

# 3. Insert new generation row inside tenant context (RLS)
docker exec fabt-rehearsal-postgres psql -U fabt -d fabt <<SQL
BEGIN;
SET LOCAL fabt.current_tenant_id = '$TENANT_ID';
WITH cur AS (
  SELECT generation FROM tenant_key_material
  WHERE tenant_id='$TENANT_ID' AND active=true
)
UPDATE tenant_key_material
  SET active=false, rotated_at=clock_timestamp()
  WHERE tenant_id='$TENANT_ID' AND active=true;
INSERT INTO tenant_key_material (tenant_id, generation, created_at, active)
  SELECT '$TENANT_ID', generation+1, clock_timestamp(), true FROM cur;
INSERT INTO kid_to_tenant_key (kid, tenant_id, generation)
  SELECT gen_random_uuid(), '$TENANT_ID', generation+1 FROM cur;
COMMIT;
SQL

# 4. Mint a fresh token, assert it carries the NEW kid
NEW_TOKEN=$(curl -sS -X POST http://localhost:18080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"outreach@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  | jq -r .accessToken)
NEW_KID=$(PAD "$(echo "$NEW_TOKEN" | cut -d. -f1)" | base64 -d 2>/dev/null | jq -r .kid)
[[ "$NEW_KID" != "$OLD_KID" && -n "$NEW_KID" ]] \
  || fail "new token should carry new kid; old=$OLD_KID new=$NEW_KID"

# 5. Assert OLD token still validates within grace
HTTP=$(curl -sS -o /dev/null -w '%{http_code}' http://localhost:18080/api/v1/me \
  -H "Authorization: Bearer $OLD_TOKEN")
[[ "$HTTP" == "200" ]] || fail "old kid token should still validate during grace; got $HTTP"

echo "[OK] Step 8.11: per-tenant kid-rotation drill (tenant=$TENANT_ID, $OLD_KID→$NEW_KID)"
```

### §6.5 — Step 8.12 (ships with Tier 2 tooling)

Once `POST /api/v1/platform/key-material/rotate` exists, drill the rotation against the real `platform_key_material` table — assert generation N+1 active, kid distinct, prior generation tokens still validate within grace.

### §6.6 — Step 8.13 (ships with Tier 3 dual-key-accept)

Once `FABT_ENCRYPTION_KEY_PREVIOUS` envelope wiring + kid back-fill migration land, drill the full re-wrap migration against real `tenant_dek` rows.

---

## 7. Sequenced backlog (rehearsal-first ordering)

| # | Item | Effort | Deliverable / acceptance criteria |
|---|---|---|---|
| 0 | Author `docs/operations/runbooks/_template.md` (frontmatter, prereqs/glossary header, procedure, rollback section, smoke gate, ledger-entry step) | 1 hr | Template file in repo; subsequent runbook items use this shape. |
| 1 | Review/parameterize `~/fabt-secrets/rotate-db-password.sh` | 30 min | Decision RECORDED in commit message: keep / parameterize / replace. Output committed. |
| 2 | Add Step 8.9 SMTP-rotation drill to `scripts/deploy-rehearsal.sh` | 1 hr | Drill green; ENV_FILE guard tested with mutation (point at non-rehearsal file → exit non-zero). |
| 3 | Author `rotate-smtp-password.md` referencing rehearsal as gate | 1 hr | Rollback section non-empty; prereqs + glossary header present. |
| 4 | Set up `rotation-ledger.json` schema + initial pre-policy grandfathered entries (SMTP 2026-04-22, platform_key_material 2026-04-26, OCI 2026-04-25) — **moved up from former item 13 per Marcus v3.2** (ledger must exist BEFORE first rotation can record an entry) | 1 hr | Ledger checked in; per-entry schema includes `secret_id`, `rotated_at`, `operator`, `rehearsal_run_id`, `rehearsal_script_sha`, `bypass:bool`, `bypass_reason`, `backup_destroyed_at`, `snapshot_path`. Pre-existing rotations have `grandfathered: pre-policy`. |
| 5 | Run rehearsal → re-rotate prod SMTP under new runbook | 30 min | First rotation entry written to ledger from #4. |
| 6 | Add Step 8.8 DB-rotation drill (uses backend network path per H6) | 1 hr | Drill green; tested with mutation. |
| 7 | Author `rotate-db-passwords.md` (includes pre-rotation pg_dump per Marcus v3.2) | 1 hr | Rollback section non-empty; pg_dump step present. |
| 8 | Run rehearsal → rotate prod DB passwords | 30 min | Ledger entries × 2 (fabt_app + fabt). |
| 9 | Author `rotate-oci-svc-principal.md` (no rehearsal — vendor-console; documents the `systemd-network:systemd-journal` ownership constraint AND the two-key-active detection step per Marcus v3.2) | 2 hr | Rollback addresses the sudo / docker-stop / file-replace sequence. |
| 10 | Author `rotate-grafana-admin.md` + add Step 8.x drill | 2 hr | Drill green. |
| 11 | Author `rotate-cloudflare-token.md` + `rotate-github-pat.md` | 2 hr | Both reference vendor-console; no drill required. |
| 12 | Add Step 8.11 JWT per-tenant kid-rotation drill (schema-grounded, includes Marcus v3.2 audit-emit + `rotated_at < now() - grace_window` read-path assertion) | 3 hr | Drill green for 2 consecutive runs; uses real per-tenant SQL with RLS context; positive control for grace-window expiry. |
| 13 | Author `rotate-jwt-secret.md` + dry-run on prod-demo | 4 hr | Authorize prod rotation ONLY after drill green for 2 consecutive runs AND dry-run successful AND read-path correctness fix shipped. |
| 14 | Calendar entries for Tier 0 cadences (operator calendar + repository CALENDAR.md) | 30 min | Forcing function. |
| 15 | OpenSpec change: `platform-jwt-key-rotation-tooling` (admin endpoint + flip job + Step 8.12 drill + `SERIALIZABLE` transaction wrap + audit-emit-on-every-flip per Marcus v3.2). **Note:** kid emission already in schema; only mint tooling needed. | 1-2 d | Tier 2 deliverable. |
| 16 | OpenSpec change: `master-kek-dual-key-accept` (envelope + kid back-fill migration + Step 8.13 drill — pilot on TOTP path first; **AEAD construction MUST cover kid in associated data per Marcus v3.2 CRITICAL**) | 2-3 d spec + 2-3 wk impl (back-fill is the bulk) | Tier 3 deliverable; AEAD assertion is a spec-level requirement, not implementation detail. |
| 17 | CI guard: `secret-rotation-staleness` reads `rotation-ledger.json` and fails CI if any Tier 0 entry is past cadence | 4 hr | Forcing function so policy doesn't quietly drift. |
| 18 | **Prometheus alert: key-material INSERT-rate (Marcus v3.2 SIGNIFICANT)** — fires when `tenant_key_material` or `platform_key_material` row-insert rate exceeds the expected rotation cadence (default: more than 1 per quarter per tenant). Catches unauthorized rotation via direct DB write. | 3 hr | Alert wired in `deploy/prometheus/`; rule tested in rehearsal via synthetic INSERT. |
| 19 | **Prometheus alert: backup-destruction-staleness (Marcus v3.2 CRITICAL)** — fires when any `~/fabt-secrets/*.pre-*` file is older than 72h. Catches the §0 bypass-clause hygiene rule when the operator forgets to `shred`. | 2 hr | Alert wired; rule tested by leaving a stale backup in rehearsal env. |
| 20 | **CODEOWNERS / branch-protection for `scripts/deploy-rehearsal.sh` (Marcus v3.2 SIGNIFICANT)** — adds the script to a CODEOWNERS allow-list so GitHub flags any change to the rehearsal harness. Interim until second operator exists. | 30 min | `.github/CODEOWNERS` updated; PR-time review enforced. |

Items 0-5 ship the first rotation under the new policy in ~5 hr (added item 4 ledger setup per Marcus v3.2 sequencing). Full Tier 0 set lands across items 6-14 in ~12 hr plus calendar entries. Marcus v3.2 hardening items 18-20 add ~5.5 hr.

---

## 8. Open questions still requiring user decision

1. **Owner clarity.** Single-operator SPOF is documented (§3 footnote) but not resolved. Hire/recruit a second operator; until then, document operator-unavailability contingency (delegated emergency rotation authority, dead-man's-switch?).
2. **Cadence calibration.** 90 vs 180 days for Tier 0 — 5-6 rotation events/year vs 2-3. Worth the tax at FABT's current scale?
3. **Pre-rotation snapshot policy.** **RESOLVED affirmatively by Marcus v3.2:** mandatory encrypted `pg_dump` of `tenant_key_material` + `platform_key_material` + `kid_to_tenant_key` + `jwt_revocations` before every Tier 1+ rotation; 30-day retention. The §4 DB procedure step 0a codifies this; §4 #4 and §4 #5 cite it as the beyond-grace rollback primitive. Question retained here for the record.
4. **`rotation-ledger.json` schema.** Per-entry: `secret_id`, `rotated_at`, `operator`, `rehearsal_run_id` (or `bypass=true + reason` for incidents), `next_due`. Match anything?
5. **Memory hygiene:** `project_oci_audit_anchor_credentials.md` should be aligned with the observed `audit-anchor.pem` mtime (2026-04-25). Small follow-up.
6. **Shell-exported `${VAR}` migration.** §1 Location B documents the audit gap; the eventual fix is a sourced env file owned `ubuntu:ubuntu 600`. Is that a separate OpenSpec change or a §7 item?

---

## 9. What this plan is NOT

- **Not a finalized policy.** Cadences in §3 are recommendations — each needs an explicit "yes" before it becomes operational.
- **Not a runbook.** Each Tier 0 secret needs its own runbook under `docs/operations/runbooks/` before first scheduled rotation (§7 backlog items 3, 6, 8, 9, 10, 12; template at item 0).
- **Not a tooling spec.** Tier 2 (#5 Platform JWT) and Tier 3 (#6 KEK) need their own OpenSpec changes before code lands.
- **Not a substitute for incident-driven rotation.** Any secret on this list can be rotated *immediately* in response to a confirmed leak; the calendar is a floor, not a ceiling. The single deviation: incident rotations bypass the rehearsal gate per §0 but require ledger entry + same-week rehearsal pass.

---

## 10. Round-2 warroom carryover (documented MEDIUMs, non-blocking)

These 8 MEDIUMs surfaced in warroom round 2 against v3. They're documented here rather than fixed in the plan body because they're drill-implementation refinements that belong in the §7 backlog items (specifically items 2, 5, 11 — the drill-authoring tasks) and the runbook templates (item 0). Whichever PR ships the actual drill scripts MUST address these or explicitly defer with rationale.

| ID | Persona | Concern | Where it lands |
|---|---|---|---|
| M1-r2 | Riley | §6.1 trap restores `$ENV_FILE` but not the DB role password. Second `make rehearse-deploy` run will fail at "old password rejected" because the OLD became the prior NEW. | §7 item 5: trap must also `ALTER ROLE fabt_app WITH PASSWORD '<original>'` OR rehearsal precondition resets DB volume. |
| M2-r2 | Riley | §6.4 has no cleanup; second run finds generation=2 already active and the `cur` CTE picks that as baseline, breaking the drill's invariants. | §7 item 11: trap must reset `tenant_key_material` to single-row generation=1 active per dev-coc tenant. |
| M3-r2 | Marcus | §6.1 step 2 has a literal `<OLD>` placeholder in the `psql` command. Drill is non-runnable as-shown; needs `OLD_PWD=$(awk …)` capture line mirroring §6.2. | §7 item 5: capture `OLD_PWD` from `$ENV_FILE` before mutation. |
| M4-r2 | Alex | §4 #4 procedure is silent on `jwt_revocations` cache interaction during rotation. If the cache is keyed by old kid, rotation may need a warm/invalidation step. | §7 item 12 (JWT runbook authoring): inspect `jwt_revocations` schema first, then add cache step if relevant. |
| M5-r2 | Sam | §7 has 18 items with no slice/PR grouping. Risk: one mega-OpenSpec change tries to land all of them. | §7 follow-up: group items into 4-5 PR slices (template+SMTP / DB / OCI+GH+CF / JWT / Tier-2 / Tier-3). |
| M6-r2 | Jordan | §0 mandates 72h rehearsal freshness but operator has no tool to check the timestamp of the last green rehearsal. | New §7 item: `make rehearse-status` reads `.last-green-rehearsal` and prints age + fails non-zero if >72h. |
| M7-r2 | Casey | §6.4 logs `TENANT_ID` UUID to drill stdout. Tolerable for laptop-only logs, borderline for CI artifacts. | Document explicitly that rehearsal output stays on operator laptop, NOT uploaded to CI artifact store. |
| M8-r2 | Alex | §4 #5 grace mechanism unstated — column on `platform_key_material` (`rotated_at` or `grace_until`) or wall-clock heuristic? | §7 item 15 (`platform-jwt-key-rotation-tooling` OpenSpec): pick one approach + cite schema implication. |

**Verdict on these:** none block v3.1 shipping as a planning doc. All become acceptance criteria on §7 items 5, 11, 12, 15. The §10 listing exists so a future drill author can't claim "wasn't told."

---

## 11. Marcus security-persona additions (v3.2)

After warroom rounds 1-3 (process/structure/drill rigor), the security persona ran a separate architectural-control review. The warroom focused on "is the procedure correct"; the security pass asked "as a security control, does this plan actually achieve secret-rotation safety and where does it leave attack surface?" Three CRITICAL + four SIGNIFICANT control gaps were identified; this section enumerates how each was addressed.

### CRITICAL gaps closed

| ID | Gap | v3.2 fix | Section(s) |
|---|---|---|---|
| MC-C1 | `.pre-rotation` backup lifecycle undefined; cleartext old secrets persist indefinitely | 72h post-smoke-clean `shred -u` step added to every §4 procedure; bypass-clause hygiene in §0; Prometheus alert in §7 item 19 | §0, §4 SMTP step 11, §4 DB step 11, §7 item 19 |
| MC-C2 | Operator shell history is a credential store (`export FABT_*_PASSWORD=` lands in `~/.bash_history`) | SHELL-HYGIENE PREAMBLE block in every §4 procedure: `unset HISTFILE; set +o history; trap 'history -c' EXIT` BEFORE any `export`; explicit `history -c` step after rotation completes; in-memory `unset` of OLD_PWD/NEW_PWD | §3 footnote, §4 SMTP, §4 DB |
| MC-C3 | No mandatory `pg_dump` of key-material tables before rotation; §4 #4 rollback fails open beyond grace | Step 0a added to §4 DB: encrypted `pg_dump` of `tenant_key_material` + `kid_to_tenant_key` + `jwt_revocations` before rotation; 30-day retention; §4 #4 + §4 #5 cite it as beyond-grace rollback primitive; §8 Q3 resolved affirmatively | §4 DB step 0a, §4 #4 PRE-ROTATION note, §4 #5, §8 Q3 |

### SIGNIFICANT gaps closed

| ID | Gap | v3.2 fix | Section(s) |
|---|---|---|---|
| MC-S1 | No detection layer for unauthorized rotation via direct DB write | Audit-emit-on-every-`active`-flip in BOTH directions (mint AND rollback) per §3 audit column; Prometheus alert on `tenant_key_material` / `platform_key_material` INSERT-rate | §3 audit column, §4 #4 step 5, §4 #5, §7 item 18 |
| MC-S2 | OCI rotation has no "new+old both live" partial-state detection | §3 Detection column updated to include OCI list-keys assertion (post-policy); §7 item 9 acceptance criterion expanded | §3, §7 item 9 |
| MC-S3 | Rehearsal-harness trust boundary unstated; gate is one-PR-deep | §6 preamble adds: (a) co-sign requirement when second operator exists, (b) ledger records `rehearsal_script_sha` (interim control), (c) CODEOWNERS/branch-protection requirement | §6 preamble, §7 item 4 (ledger schema), §7 item 20 (CODEOWNERS) |
| MC-S4 | Downgrade attack via documented rollback path (R1 re-activates leaked old generation, no detection) | Read-path correctness rule added: validation MUST check `active = false AND rotated_at < now() - grace_window` and REJECT past grace, not just check `active`; audit emits on every `active=true ↔ false` transition (R1/R2 trigger emit) | §4 #4 read-path note + R1/R2, §4 #5 |

### Cryptographic concerns addressed

- **JWT kid validation** — explicit read-path rule in §4 #4: reject if `rotated_at < now() - grace_window`. Drill (§7 item 12) adds positive control for the rejection.
- **Platform JWT grace window** — §4 #5 wraps INSERT-new + UPDATE-old in `SERIALIZABLE` transaction; M8-r2 (grace-window-mechanism-unstated) resolved by tying to the same `rotated_at` semantics as §4 #4.
- **Dual-key envelope AEAD** — §4 #6 mandates: AEAD construction, kid in associated data, MAC verifies under selected key. Mitigates attacker-supplied kid pointing to a leaked previous-key UUID. Drill (§7 item 16) asserts MAC rejection on tampered envelope.

### Reclassified items

- **M4-r2** (`jwt_revocations` cache interaction during rotation) — was MEDIUM hygiene; reclassified as **security-adjacent**. Stale cache lets a leaked pre-rotation kid validate past its `rotated_at`. Now addressed in §4 #4 step 4 (DELETE FROM jwt_revocations during rotation transaction).

### What remains as acceptance criteria on §7 items

These Marcus v3.2 additions create new acceptance criteria on existing §7 backlog items rather than fixing the plan body further:

- §7 item 12 (JWT drill): now includes positive control for past-grace token rejection
- §7 item 15 (Platform JWT OpenSpec): now includes `SERIALIZABLE` transaction wrap + audit-emit-on-every-flip as spec requirements
- §7 item 16 (Master KEK OpenSpec): AEAD construction with kid in associated data is now a spec-level requirement
- §7 items 18-20 (new): Prometheus alerts + CODEOWNERS = the detection-layer and gate-trust controls

### Net assessment (Marcus, post-v3.2)

The plan now treats the rotation event as ending at `backup destruction + shell-history scrub + audit emit + (for Tier 1+) `pg_dump` retention`, not at `docker compose up`. With these additions plus the §7 items 18-20 hardening backlog, a third-party rotation audit would find a strong procedural framework backed by both DB-layer invariants and observability. The sole-operator SPOF (§3 footnote) remains an accepted operational risk; everything else has either a control or a documented backlog item to add one.

---

## Appendix A — Ground-truth provenance

All facts in §1 were observed via three SSH sessions on 2026-05-22 to the prod VM:

1. **Pass 1** captured `~/fabt-secrets/` file listing (no contents), `.env.prod` variable NAMES only via `awk -F= '/^[A-Z]/ {print $1}'`, `.env.prod` mtime via `stat`, container ages via `docker ps`, Postgres role expiry via `SELECT rolname, rolvaliduntil FROM pg_roles`, `platform_key_material` generation count, and alertmanager status from `/api/v2/status`.
2. **Pass 2** captured `docker-compose.prod.yml` variable NAMES only via `grep -oE` patterns (keys and `${VAR}` references), `rotate-db-password.sh` line count + first 2 lines + grep for external-tool names (`docker exec`, `psql`, `ALTER ROLE`), `oci/` directory listing, and `docker-compose.prod.yml` mtime.
3. **Pass 3** captured JWT key-material table names via `\dt`, full `\d` schemas for `tenant_key_material` + `kid_to_tenant_key` (PKs, partial unique indexes, check constraints, FKs, RLS policies), and tenant_key_material row counts.

**At no point was any secret value read.** Per `feedback_never_print_rendered_secrets`: cat/head/tail/grep against the secret-bearing files (`.env.prod`, `alertmanager.yml`, `docker-compose.prod.yml`, `oci/audit-anchor.pem`, `rotate-db-password.sh`) was avoided. All inspections used name-only or metadata-only extraction patterns.
