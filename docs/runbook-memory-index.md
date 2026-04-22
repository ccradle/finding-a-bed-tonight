# Deploy Runbook Memory Index

Scannable index of every memory file relevant to deploy operations.
Scan this before authoring a new `docs/oracle-update-notes-vX.Y.Z.md` to
populate the `consulted:` block.

**Editorial note:** Add a new entry here whenever a `feedback_*.md` or
`project_*.md` file is created that encodes a deploy, ops, or runbook
lesson. The test: "would a future runbook author need to know this?"

**Index discipline:** each entry is one line, ≤150 chars. Topic then implication.

---

## Deploy execution

- `feedback_prod_docker_build_pattern.md` — `up --build` is NO-OP; use `docker build --no-cache` + `--force-recreate`. Three v0.48 traps.
- `feedback_bind_mount_inode_pitfall.md` — single-file bind-mount holds inode; `git checkout` = new inode → `--force-recreate` needed, not reload.
- `feedback_deploy_old_jars.md` — always `mvn clean package`; stale JARs in `target/` cause `COPY *.jar` to pick up the wrong version.
- `feedback_stale_sw_on_deploy.md` — old service worker serves cached JS post-deploy; test in incognito or clear site data.
- `feedback_deploy_checklist_v031.md` — post-deploy checklist: verify JAR version, actuator :9091, class verification, image prune.
- `feedback_cleanup_old_artifacts.md` — remove stale JARs and Docker images after each Oracle deploy.

## Pre-deploy safety

- `feedback_never_print_rendered_secrets.md` — **CRITICAL**: never cat/grep `.env.prod` or `alertmanager.yml`; placeholder-count + wc-l checks only.
- `feedback_release_after_scans.md` — GitHub release only after CI scans green; tag first, `gh release create` second.
- `feedback_flyway_immutable_after_apply.md` — **CRITICAL**: never edit applied Flyway migration (even comments); mismatch → backend won't start.
- `feedback_never_guess_deployment.md` — **HIGHEST PRIORITY**: read deployment docs before acting; never guess on Oracle VM.
- `feedback_no_ip_in_repo.md` — VM IP must not appear in git-tracked files; fine in memory only.

## Post-deploy verification

- `feedback_smoke_spec_default_target.md` — smoke specs need `BASE_URL=https://findabed.org` override; default is localhost, CI will hit prod WAF.
- `feedback_check_ports_before_assuming.md` — **CRITICAL**: Playwright uses `BASE_URL=http://localhost:8081` (nginx), never bare Vite port.
- `feedback_deploy_verify_isolation.md` — post-deploy smoke specs live in `deploy/` dir, NOT `tests/`; wrong dir corrupts seed passwords.
- `project_oracle_deployment_lessons.md` — 10 gotchas from first Oracle deploy: fabt_app password, port 9091, CORS, iptables, container names.

## Infrastructure & config

- `project_live_deployment_status.md` — current prod: version, containers, compose chain, HWM, tenants, Alertmanager, rollback image.
- `feedback_prometheus_external_labels_gotcha.md` — `external_labels` invisible to local PromQL/rule eval; use `scrape_configs` labels.
- `feedback_alertmanager_template_funcs.md` — Alertmanager v0.27.0 has 9 template funcs, NO Sprig; use Go `with`/`else` not `default`.
- `feedback_pgaudit_include_dir_existing_volume.md` — pgaudit `include_dir` fresh pgdata only; existing volumes need direct `postgresql.conf` edit.

## Documentation accuracy

- `feedback_verify_doc_facts_against_source.md` — grep source before naming rules/slugs/metrics; v0.49 had 7/9 wrong rule names + invented slugs.
- `feedback_no_ssh_tunnels.md` — share SSH tunnel commands, don't execute them; confirm operator SSH session before starting deploy.

## Process & tooling

- `feedback_devstart_pid_desync.md` — `.pid-backend` stale → port 8080 collision; use `./dev-start.sh stop` (script handles PID cleanup).
- `feedback_use_dev_start_script.md` — always use `./dev-start.sh` for local stack; never raw `docker compose`.
- `feedback_maven_not_gradle.md` — build tool is Maven (`pom.xml`); never use gradle/gradlew.
- `feedback_build_before_commit.md` — **CRITICAL**: run `npm run build` (tsc + vite) before committing any frontend change.
- `feedback_no_brute_force.md` — fix the script itself, don't hack around broken scripts.
