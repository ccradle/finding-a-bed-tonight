# Oracle VM kernel-reboot runbook

**Version:** v3.1 (2026-05-30, warroom rounds 1+2+3 applied; READY TO EXECUTE)
**Scope:** Activate an already-installed kernel by rebooting the FABT prod VM. This is the *first* such runbook, authored against the live state captured 2026-05-30 23:07Z (uptime 37 days, kernel `6.8.0-1049-oracle` running, `6.8.0-1050-oracle` installed by unattended-upgrades 2026-05-28).
**Out of scope:** Applying the 22 non-security upgradable packages (Docker, netplan, JDK, etc.). Those need their own window and their own runbook.

**How to use this runbook:**
- `<VM_HOST>` throughout = the FABT prod VM's public IP. Per the `feedback_no_ip_in_repo` standing rule, the literal IP is held in operator memory (`project_live_deployment_status.md`), not in this file. Substitute it at run-time when copying commands.
- Run commands in order top-to-bottom. Do not skip §2.5 (the GRUB pin) — without it, the boot target is ambiguous.
- Checkbox each item as you go. If anything fails its acceptance criterion, stop and read §6 before proceeding.

---

## §0 Gate & risk

| Field | Value |
|---|---|
| Trigger | `/var/run/reboot-required` exists AND `reboot-required.pkgs` lists a `linux-image-*` |
| Frequency expected | ~monthly (Oracle pushes kernel patches ~every 3-5 weeks) |
| Expected downtime | 60-180 s SSH-unreachable + ~30 s container cold-boot + nginx warm-up |
| Customer impact | Brief unavailability of findabed.org; no data loss (postgres on durable volume) |
| Rollback path | (a) If shell is still reachable: `sudo grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-1049-oracle" && sudo reboot` (one-shot, reverts after one boot). (b) If shell is gone: Oracle Cloud serial console — **note**: OCI Ubuntu 22.04 instances have a documented issue interrupting GRUB at boot (https://community.oracle.com/customerconnect/discussion/907373/). If (b) fails, attach the boot volume to a recovery instance and edit `/etc/default/grub` from there. Multi-hour recovery. |
| Rehearsal applicable | NO — rehearsal harness exercises the app stack, not VM-level reboot. We gain confidence instead via (i) the §2.5 explicit-pin-and-verify of the GRUB next-boot target and (ii) inspecting all 8 container restart policies, which must all be `unless-stopped` (verified 2026-05-30). |
| Abort criteria | If §1 pre-flight fails on any item: postpone 24 h, do not retry same window. If §2.5 cannot one-shot pin the new kernel: STOP, investigate, do not reboot. |
| Customer comms | None pre-reboot — outage is sub-3-minute and we do not pre-announce kernel reboots. Post-hoc record lives in the §7 ledger only. If a known partner is in active demo prep that day, postpone. |

---

## §1 Pre-flight (must all pass before §3)

- [ ] No customer demo scheduled in next 30 minutes (check calendar + recent Slack/email).
- [ ] No PR in mid-merge on either repo (`gh pr list --state open --search "is:open is:pr"` for both repos).
- [ ] No GitHub Actions workflow in-progress on main for either repo:
  ```bash
  gh run list --repo ccradle/finding-a-bed-tonight --branch main --status in_progress --limit 5
  gh run list --repo ccradle/findABed --branch main --status in_progress --limit 5
  ```
  (Empty output = safe. An in-progress deploy or release workflow MUST complete first.)
- [ ] Local main is clean (`git status` in both `findABed/` and `finding-a-bed-tonight/`).
- [ ] SSH key works: `ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> 'uptime'`.
- [ ] Browser tab open to https://findabed.org (we will watch it recover).
- [ ] `lastgood` tag exists for current release: `git tag --list 'v0.57.2-lastgood'` returns a tag.

---

## §2 Snapshot (rollback breadcrumb)

Capture pre-reboot state to a local file (gitignored):

```bash
SNAP=~/fabt-reboot-snapshot-$(date -u +%Y%m%dT%H%M%SZ).txt
{
  echo "=== captured at $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
  ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> '
    echo "--- uptime ---"; uptime
    echo "--- kernel ---"; uname -r
    echo "--- containers ---"; docker ps --format "table {{.Names}}\t{{.Status}}\t{{.RunningFor}}"
    echo "--- flyway HWM ---"
    docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -t -c "SELECT MAX(version) FROM flyway_schema_history;"
    echo "--- pgdata volume ---"
    docker inspect finding-a-bed-tonight-postgres-1 --format "{{ range .Mounts }}{{ .Type }}: {{ .Source }} -> {{ .Destination }}{{ println }}{{ end }}"
  '
  echo "--- prod /api/v1/version ---"
  curl -sS https://findabed.org/api/v1/version
} > "$SNAP"
echo "Snapshot: $SNAP"
```

- [ ] Snapshot file written.
- [ ] Snapshot file contents reviewed for any unexpected secret values (this script only emits version + container metadata + Flyway version — no secrets — but per `feedback_never_print_rendered_secrets` we audit anyway).
- [ ] Postgres data lives on a **named volume**, confirmed by `Type: volume` in the inspect output. (A bind mount for `init-app-user.sql` is also present — that is fine; it is only consumed on first-boot of an empty volume.)
- [ ] Flyway HWM recorded (currently expected: V98).
- [ ] All 8 containers have restart policy `unless-stopped` (verified 2026-05-30; if any are `no` or `on-failure:N` the cold-boot assumption breaks). Verify with:
  ```bash
  ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> 'for c in $(docker ps --format "{{.Names}}"); do echo "$c: $(docker inspect $c --format "{{.HostConfig.RestartPolicy.Name}}")"; done'
  ```

---

## §2.5 Pin the next boot to the new kernel (mandatory — do not skip)

Because `GRUB_TIMEOUT=0` and `GRUB_TIMEOUT_STYLE=hidden`, GRUB will not show a menu and the simple "Ubuntu" entry's implicit target is ambiguous (no `/vmlinuz` symlink on this VM). We must explicitly pin the next boot:

```bash
ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> '
  # Verify the menu entry path exists exactly as expected
  if ! grep -q "Ubuntu, with Linux 6.8.0-1050-oracle" /boot/grub/grub.cfg; then
    echo "ABORT: target menuentry not found in grub.cfg" >&2; exit 1
  fi
  sudo grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-1050-oracle"
  echo "--- grub-editenv now (should show next_entry=...1050...) ---"
  sudo grub-editenv list
'
```

- [ ] `next_entry=...1050-oracle...` is set.
- [ ] If not: STOP. The submenu path string must match `grub.cfg` exactly. Use `grep "^menuentry\|^submenu" /boot/grub/grub.cfg` to find the verbatim path.

`grub-reboot` is **one-shot**: if 1050 boots cleanly, the next reboot reverts to the persistent default (which `update-grub` will have set to 1050 too, so we end up at 1050 either way). If 1050 fails to boot but the system recovers somehow, the persistent default still applies. There is no "stick on old kernel until I say otherwise" state.

---

## §3 Reboot

**Use `shutdown -r +1` exclusively** (the 1-minute grace lets a logged-in operator cancel with `sudo shutdown -c` if a partner appears unexpectedly). Do **not** substitute `sudo reboot`.

```bash
ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> 'sudo shutdown -r +1 "FABT: scheduled kernel reboot to 6.8.0-1050-oracle"'
date -u +%Y-%m-%dT%H:%M:%SZ   # record the timestamp now
```

- [ ] Command accepted (broadcast message printed).
- [ ] Timestamp of *issuing* the command recorded: <!-- T_issue: -->
- [ ] SSH will drop ~60 s later when the system goes down. That is expected.

---

## §4 Wait for boot

In a local terminal, poll until SSH is reachable again:

```bash
echo "Waiting for SSH starting $(date -u +%Y-%m-%dT%H:%M:%SZ)..."
until ssh -i ~/.ssh/fabt-oracle -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
  ubuntu@<VM_HOST> 'echo up' 2>/dev/null; do
  printf '.'
  sleep 10
done
echo
echo "VM SSH back up at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
```

- [ ] SSH reachable.
- [ ] Timestamp of recovery recorded: <!-- T_recovery: -->
- [ ] `T_recovery - T_issue` ≈ 60-180 s is normal. **If >300 s elapsed without recovery: jump to §6.**

---

## §5 Post-boot verification (all must pass)

```bash
ssh -i ~/.ssh/fabt-oracle -o StrictHostKeyChecking=accept-new ubuntu@<VM_HOST> '
  echo "--- new kernel ---"; uname -r
  echo "--- grub one-shot consumed (should be empty) ---"; sudo grub-editenv list
  echo "--- reboot-required flag (should be gone) ---"; ls -la /var/run/reboot-required 2>&1 | head -1
  echo "--- containers ---"; docker ps --format "table {{.Names}}\t{{.Status}}"
  # Backend health is checked via docker exec, NOT via https://findabed.org/actuator/health —
  # nginx does not proxy /actuator to the outside world (correctly). Do not "fix" this.
  echo "--- backend health (in-container) ---"; docker exec fabt-backend wget -qO- http://localhost:9091/actuator/health
  echo "--- flyway HWM ---"
  docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -t -c "SELECT MAX(version) FROM flyway_schema_history;"
  echo "--- dmesg tail (look for anything red) ---"; sudo dmesg --since "5 minutes ago" | tail -30
'
```

- [ ] Kernel == `6.8.0-1050-oracle`.
- [ ] `grub-editenv list` is **empty** (proves the one-shot was consumed by this boot; if it still shows `next_entry=...`, GRUB took a different path than we set — investigate before doing this again).
- [ ] `/var/run/reboot-required` returns "No such file or directory".
- [ ] All 8 containers Up (`fabt-backend`, `fabt-frontend`, `finding-a-bed-tonight-{postgres,prometheus,alertmanager,grafana,otel-collector,jaeger}-1`).
- [ ] Postgres `Status` includes `(healthy)`.
- [ ] Backend `/actuator/health` returns `{"status":"UP"`.
- [ ] Flyway HWM == V98 (unchanged from §2 snapshot).
- [ ] `dmesg` has no obvious kernel oopses, MCEs, or hardware errors.

Then from local (end-to-end through nginx, not just root URL):

```bash
curl -sf https://findabed.org/api/v1/version                # expect: contains "0.57.2"
curl -sIf https://findabed.org/                              # expect: HTTP/2 200
curl -sf "https://findabed.org/api/v1/active-counties" | head -c 200   # expect: JSON array, proves DB-reachable
```

- [ ] Version endpoint returns `0.57.2`.
- [ ] Root URL returns 200.
- [ ] `/api/v1/active-counties` returns a JSON array (proves request path: nginx → backend → postgres).
- [ ] Manual smoke: refresh findabed.org browser tab; home loads, shelter-card click works, footer renders contact email. (One-shot eyeball — does not need a recorded artifact.)

---

## §6 Rollback / escalation

| Symptom | Action |
|---|---|
| VM not SSH-reachable after 5 min | Wait another 5 min (Oracle hardware-prep can be slow). |
| VM not SSH-reachable after 10 min | Open Oracle Cloud console → Compute → Instances → fabt-prod → check **Instance state** (RUNNING/STOPPED/STOPPING). |
| Instance state STOPPED | Click **Start**. Wait. Resume §4. |
| Instance RUNNING + SSH up + booted to 1050 but degraded | `sudo grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-1049-oracle" && sudo reboot`. This is the **happy rollback path** — works only because we still have shell. |
| Instance RUNNING + no SSH (kernel panic at boot, or networking broken) | Open **Console connection** (serial). Known issue: OCI Ubuntu 22.04 instances often cannot interrupt GRUB via serial — see https://community.oracle.com/customerconnect/discussion/907373/. If GRUB interrupt fails: STOP the instance from the console, **detach the boot volume**, attach it to a recovery VM in the same compartment, mount, `chroot`, run `grub-reboot` for 1049, unmount, detach, reattach to original, boot. **Multi-hour recovery; this is the worst case.** |
| SSH up, containers not running | `docker ps -a` — if exited, `docker start <name>`. If Docker daemon dead, `sudo systemctl status docker`. |
| `/actuator/health` returns DOWN | Check `docker logs fabt-backend --tail 200`. Likely postgres race; usually self-heals within 60 s of postgres becoming healthy. |
| Flyway HWM changed | STOP. Migration ran unexpectedly. Capture state, do not write further. Investigate before any further action. |

Escalation contacts: the project operator is the sole on-call. There is no secondary today. Document anything you cannot resolve and capture screenshots of the Oracle console state.

---

## §7 Post-run record

- [ ] Append a YAML record to `~/fabt-reboot-ledger.yaml` (eventual home: rotation-ledger.json — see secret-rotation plan §7 item 4. Using YAML for human read until that schema lands; convert at that time):

```yaml
- event: vm_kernel_reboot
  date_utc: 2026-05-30T__:__Z
  vm: fabt-prod
  kernel_before: 6.8.0-1049-oracle
  kernel_after: 6.8.0-1050-oracle
  ssh_down_seconds: ___
  total_recovery_seconds: ___
  containers_restarted: 8
  flyway_hwm_before: V98
  flyway_hwm_after: V98
  # Get runbook_sha with: git rev-parse HEAD:docs/operations/runbooks/oracle-vm-kernel-reboot.md
  runbook_sha: ___
  verified_by: sole_operator
  notes: ___
```

- [ ] Memory write: update `project_live_deployment_status.md` to note "uptime reset by kernel reboot YYYY-MM-DD; kernel now 1050".
- [ ] If anything surprised you, append to §8 here and to the secret-rotation plan §10 carryover.

---

## §8 Open follow-ups (for the 22-pkg upgrade window)

The reboot itself does **not** touch any of the 22 currently-upgradable packages. Track these separately:

1. **Docker stack upgrade** (`docker-ce`, `containerd.io`, plugins) — needs its own runbook; daemon restart cycles containers. Risk: high enough to warrant its own dry-run.
2. **Netplan upgrade** (`netplan.io`, `netplan-generator`, `libnetplan0`, `python3-netplan`, `iproute2`) — Oracle Cloud has had netplan regressions before; do not bundle with Docker.
3. **JDK 25 upgrade** (`temurin-25-jdk`) — build-host only; containers ship their own JRE. Low blast radius but verify the Maven build still passes on the upgraded JDK.
4. **System utils** (`snapd`, `distro-info-data`, `ubuntu-advantage-tools`, `ubuntu-pro-client*`, `adoptium-ca-certificates`) — bundle into a "minor packages" window.
5. Consider extracting the §1-§5 shape of this file into `docs/operations/runbooks/_template.md` once we have a second runbook to compare against (avoids premature templating; see secret-rotation-plan §7 item 0).

### Runbook bugs surfaced during first live run (2026-05-30)

- **§5 smoke endpoint chosen poorly**: `/api/v1/active-counties` requires auth (401). Replace with a public no-auth endpoint that proves nginx → backend → DB (e.g., `/api/v1/version` already validates the path, OR add a `/api/v1/health` public endpoint). Until fixed, the existing in-container `/actuator/health` + Postgres `(healthy)` + `psql` Flyway query are the authoritative DB-reachability proofs.
- **§1 lastgood tag check over-specified**: rollback for a kernel reboot is GRUB-based, not image-based. Reword to "lastgood tag exists if you also intend to roll back the app; not required for kernel-only reboots."
- **`dmesg --since "5 minutes ago"` returns nothing right after boot** because the relative window predates kernel-boot time. Switch to `dmesg -T | tail -50` for post-boot inspection.

---

## §9 Provenance & execution history

- Live state captured 2026-05-30 23:07Z via `ssh -i ~/.ssh/fabt-oracle ubuntu@<VM_HOST>` (read-only checks — no secrets printed).
- 22 upgradable packages enumerated; security pocket already drained by unattended-upgrades (last run 2026-05-30 06:23).
- Authored under standing rule `feedback_no_ip_in_repo`: literal VM IP held in operator memory (`project_live_deployment_status.md`), never in this file. Operator substitutes `<VM_HOST>` at run time.

### Run #1 — 2026-05-30 23:21Z (v3.1)
- Kernel: 6.8.0-1049-oracle → 6.8.0-1050-oracle
- Total recovery: 118 s (60 s grace + ~58 s actual SSH-down)
- All 8 containers cold-booted clean via `unless-stopped`; Flyway HWM unchanged (V98)
- One-shot GRUB pin consumed exactly as designed (`next_entry=` empty post-boot)
- Two runbook bugs discovered (see §8); no production regression
