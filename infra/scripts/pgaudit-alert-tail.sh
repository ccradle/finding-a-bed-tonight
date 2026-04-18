#!/usr/bin/env bash
#
# pgaudit-alert-tail.sh — tails the Postgres container's pgaudit log
# output and posts a webhook alert when a `NO FORCE ROW LEVEL SECURITY`
# DDL statement appears. Per Phase B warroom V4 resolution (pragmatic
# path — Loki + promtail NOT in current stack): a minimal cron-grep
# style tailer keeps the Phase B detection-of-last-resort for cleared
# FORCE RLS without expanding the observability stack.
#
# Install as a systemd service (see deploy/systemd/fabt-pgaudit-alert
# .service) so `Restart=always` holds the tail loop open across
# postgres restarts, docker container recycles, and script crashes.
#
# Usage (manual test):
#   FABT_PANIC_ALERT_WEBHOOK=https://hooks.slack.com/services/... \
#   FABT_POSTGRES_CONTAINER=fabt-postgres \
#       ./pgaudit-alert-tail.sh
#
# Required env vars:
#   FABT_PANIC_ALERT_WEBHOOK   Slack/PagerDuty webhook URL
#   FABT_POSTGRES_CONTAINER    docker container name/id to tail
#                              (default: postgres)
#
# Optional env vars:
#   FABT_ALERT_COOLDOWN_SECS   minimum seconds between duplicate
#                              alerts for the same table (default: 300)
#   FABT_ALERT_TAIL_SINCE      initial --since window (default: 1m)
#   FABT_HEARTBEAT_FILE        path to touch every 30s as liveness
#                              signal (default: /var/lib/fabt/
#                              pgaudit-alert-tail.heartbeat)
#
# Exit codes:
#   0 — impossible (script runs indefinitely under systemd)
#   1 — unrecoverable error before tail loop starts
#   2 — env-var / argument error

set -euo pipefail

if [[ -z "${FABT_PANIC_ALERT_WEBHOOK:-}" ]]; then
    echo "FAIL: FABT_PANIC_ALERT_WEBHOOK unset." >&2
    exit 2
fi

CONTAINER="${FABT_POSTGRES_CONTAINER:-postgres}"
COOLDOWN="${FABT_ALERT_COOLDOWN_SECS:-300}"
SINCE="${FABT_ALERT_TAIL_SINCE:-1m}"
HEARTBEAT="${FABT_HEARTBEAT_FILE:-/var/lib/fabt/pgaudit-alert-tail.heartbeat}"

# Ensure heartbeat directory exists. If we can't create it, exit — don't
# run silently for weeks while the absence-check monitor has no data.
heartbeat_dir="$(dirname "$HEARTBEAT")"
if ! mkdir -p "$heartbeat_dir" 2>/dev/null; then
    echo "FAIL: cannot create heartbeat dir $heartbeat_dir" >&2
    exit 1
fi
touch "$HEARTBEAT" || {
    echo "FAIL: cannot write heartbeat file $HEARTBEAT" >&2
    exit 1
}

# Verify the container is accessible BEFORE starting the tail loop.
# A typo in FABT_POSTGRES_CONTAINER would cause `docker logs` to error
# silently and the alert would never fire — better to crash at start.
if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    echo "FAIL: docker container '$CONTAINER' not found. Set FABT_POSTGRES_CONTAINER." >&2
    exit 1
fi

# Webhook POST helper. Uses a single POST with retry-once on network
# error. Larger retry budgets are not appropriate here — the alert is
# time-sensitive and systemd will restart the whole script if the
# webhook is persistently unreachable.
post_alert() {
    local operator_msg="$1"
    local table_hint="$2"
    local payload
    payload=$(printf '{"text":"%s","table":"%s","hostname":"%s","timestamp":"%s"}' \
        "$operator_msg" "$table_hint" "$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo unknown-host)" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
    for attempt in 1 2; do
        if curl --silent --show-error --max-time 10 \
            -X POST -H 'Content-Type: application/json' \
            -d "$payload" "$FABT_PANIC_ALERT_WEBHOOK" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    # Both attempts failed — log but don't exit (systemd restart
    # wouldn't help a webhook outage).
    echo "WARN: webhook POST failed after 2 attempts at $(date -u +%FT%TZ)" >&2
    return 1
}

# Heartbeat updater — runs in background, touches the file every 30s.
# If this script dies, the heartbeat stops updating and the absence-
# check monitor (operator cron / Prometheus file-age alert) pages.
(
    while true; do
        touch "$HEARTBEAT" 2>/dev/null || true
        sleep 30
    done
) &
HEARTBEAT_PID=$!
# shellcheck disable=SC2064
trap "kill $HEARTBEAT_PID 2>/dev/null || true" EXIT

echo "pgaudit-alert-tail starting at $(date -u +%FT%TZ)"
echo "  container: $CONTAINER"
echo "  cooldown:  ${COOLDOWN}s"
echo "  heartbeat: $HEARTBEAT"

# Table-keyed last-alert timestamps for cooldown deduping. Bash 4+
# associative array; systemd unit pins bash >= 4.
declare -A LAST_ALERT

# The tail loop. `docker logs -f --since="$SINCE"` with no tail output
# limit streams container logs from $SINCE ago to now, then follows
# indefinitely. On container restart docker re-opens the log stream;
# the --since window prevents a flood of historical lines after a
# restart.
#
# The grep pattern matches pgaudit's AUDIT line format for the
# NO FORCE RLS DDL pattern. pgaudit emits:
#   AUDIT: SESSION,N,1,DDL,ALTER TABLE,TABLE,schema.table,
#   ALTER TABLE public.<name> NO FORCE ROW LEVEL SECURITY,<params>
docker logs -f --since="$SINCE" "$CONTAINER" 2>&1 \
    | grep --line-buffered -iE 'AUDIT:[[:space:]]+SESSION,.*NO FORCE ROW LEVEL SECURITY' \
    | while IFS= read -r line; do
        # Extract target table for cooldown keying + alert payload.
        # pgaudit puts the fully-qualified name in field 7 of the
        # comma-separated AUDIT record, but the log line also has
        # the raw SQL. Grep the SQL text first (more reliable across
        # pgaudit format changes). Use POSIX [[:space:]] because BSD
        # grep (Git Bash, macOS) does NOT interpret \s in ERE mode.
        table_name=$(echo "$line" \
            | grep -oiE 'ALTER[[:space:]]+TABLE[[:space:]]+[a-zA-Z_][a-zA-Z0-9_.]+' \
            | awk '{print $NF}' \
            | head -n1)
        if [[ -z "$table_name" ]]; then
            table_name="unknown"
        fi

        now=$(date +%s)
        last="${LAST_ALERT[$table_name]:-0}"
        elapsed=$(( now - last ))
        if (( elapsed < COOLDOWN )); then
            # In cooldown — log and skip webhook POST.
            echo "[$(date -u +%FT%TZ)] match on '$table_name' suppressed (cooldown ${elapsed}s/${COOLDOWN}s)"
            continue
        fi

        msg="Phase B detection-of-last-resort: NO FORCE ROW LEVEL SECURITY on ${table_name} at $(hostname -s 2>/dev/null || hostname 2>/dev/null || echo unknown-host) ${timestamp:-$(date -u +%FT%TZ)}. See docs/security/phase-b-silent-audit-write-failures-runbook.md"
        echo "[$(date -u +%FT%TZ)] ALERT: ${msg}"
        if post_alert "$msg" "$table_name"; then
            LAST_ALERT[$table_name]=$now
        fi
    done

# If we reach here, the docker logs pipeline exited — systemd
# Restart=always picks us back up.
echo "tail pipeline ended at $(date -u +%FT%TZ); systemd will restart"
exit 1
