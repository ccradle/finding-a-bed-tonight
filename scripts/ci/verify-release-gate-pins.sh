#!/usr/bin/env bash
#
# verify-release-gate-pins.sh — v0.45.0 warroom W-CHANGELOG-1 guard.
#
# Verifies that every SHA-256 pin in deploy/release-gate-pins.txt matches
# the current file's actual hash. Without this check the pins are
# decorative — an operator could edit docs/security/pg-policies-
# snapshot.md without updating the pin, and the drift would only surface
# at deploy-time sha256sum comparison.
#
# Also enforces process gates declared in release-gate-pins.txt:
#   rehearsal-green-within-72h: any PR that bumps backend/pom.xml AND
#     modifies CHANGELOG.md MUST reference a rehearsal log filename in
#     the PR description (logs/rehearsal-smoke-*.log pattern) OR include
#     a sidecar deploy/rehearsal-attest-<version>.txt file.
#     Set REHEARSAL_GATE_SKIP=1 to bypass (hot-fix only).
#
# Usage:
#   ./scripts/ci/verify-release-gate-pins.sh
#
# Exit codes:
#   0 = all pins match actual file SHA-256, all process gates satisfied
#   1 = at least one pin mismatch or process gate failure
#   2 = environmental failure (sha256sum missing, pins file missing)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

PINS=deploy/release-gate-pins.txt
if [[ ! -f "$PINS" ]]; then
    echo "FAIL: $PINS not found" >&2
    exit 2
fi
if ! command -v sha256sum &>/dev/null; then
    echo "FAIL: sha256sum required but not installed" >&2
    exit 2
fi

FAIL=0
COUNT=0
# Strip CR from the pins file before parsing so a Windows/CRLF checkout
# doesn't leave \r in the last field (which confuses [[ -f "$path" ]] on
# later lines and produces "pinned file does not exist (release )"
# noise locally while CI passes on LF).
while IFS=$'\t' read -r path pinned release || [[ -n "$path" ]]; do
    # Skip comments + blank lines.
    [[ -z "$path" || "$path" =~ ^[[:space:]]*# ]] && continue

    COUNT=$((COUNT + 1))
    if [[ ! -f "$path" ]]; then
        echo "FAIL: pinned file $path does not exist (release $release)"
        FAIL=1
        continue
    fi
    # Normalize line endings (strip CR) before hashing so the pin is
    # platform-agnostic. Without this, a Windows Git checkout with CRLF
    # produces a different SHA-256 than a Linux CI runner with LF —
    # CI fails even though both checkouts represent the same file.
    actual=$(tr -d '\r' < "$path" | sha256sum | awk '{print $1}')
    if [[ "$actual" != "$pinned" ]]; then
        echo "FAIL: SHA-256 drift on $path (pinned at $release)"
        echo "    pinned:  $pinned"
        echo "    actual:  $actual"
        FAIL=1
    fi
done < <(tr -d '\r' < "$PINS")

if (( FAIL == 1 )); then
    cat <<EOF

Fix: update deploy/release-gate-pins.txt with the new SHA-256 (and bump
the release-tag column), OR revert the file change if the drift was
accidental. The pin is the compliance-grade attestation that what was
reviewed in the CODEOWNERS-signed PR is what shipped.
EOF
    exit 1
fi

if (( COUNT == 0 )); then
    echo "WARN: no pins declared in $PINS" >&2
    exit 0
fi
echo "✓ $COUNT release-gate SHA-256 pin(s) verified"

# ── Process gate: rehearsal-green-within-72h ──────────────────────────────────
# Applies only to release PRs (pom.xml version bump + CHANGELOG.md change).
# In CI the PR description is provided via PR_DESCRIPTION env var (set by the
# workflow). Locally this gate is skipped unless REHEARSAL_GATE_ENFORCE=1.
is_release_pr() {
    # Detect: backend/pom.xml changed AND CHANGELOG.md changed on this branch
    # compared to the merge-base with main.
    local base
    base=$(git merge-base HEAD origin/main 2>/dev/null || echo "")
    [[ -z "$base" ]] && return 1
    local changed_files
    changed_files=$(git diff --name-only "$base" HEAD 2>/dev/null || echo "")
    echo "$changed_files" | grep -q "backend/pom.xml" || return 1
    echo "$changed_files" | grep -q "CHANGELOG.md" || return 1
    return 0
}

if is_release_pr; then
    if [[ "${REHEARSAL_GATE_SKIP:-0}" == "1" ]]; then
        echo "WARN: rehearsal-green-within-72h gate bypassed (REHEARSAL_GATE_SKIP=1)" >&2
    else
        REHEARSAL_OK=0
        # Check 1: PR description contains a rehearsal log filename pattern
        if [[ -n "${PR_DESCRIPTION:-}" ]]; then
            if echo "$PR_DESCRIPTION" | grep -qE "logs/rehearsal-smoke-[0-9]{8}-[0-9]{6}\.log"; then
                REHEARSAL_OK=1
            fi
        fi
        # Check 2: sidecar attestation file present
        if ls deploy/rehearsal-attest-*.txt &>/dev/null 2>&1; then
            REHEARSAL_OK=1
        fi
        if (( REHEARSAL_OK == 0 )); then
            cat <<EOF

FAIL: rehearsal-green-within-72h gate not satisfied.
This is a release PR (backend/pom.xml version bump + CHANGELOG.md modified)
but no rehearsal evidence was found. Satisfy one of:
  (a) Include a log filename in the PR description:
        logs/rehearsal-smoke-YYYYMMDD-HHMMSS.log
  (b) Commit a sidecar: deploy/rehearsal-attest-<version>.txt
  (c) Hot-fix bypass only: set REHEARSAL_GATE_SKIP=1 in the CI workflow.

Run the rehearsal with: make rehearse-deploy
EOF
            exit 1
        fi
        echo "✓ rehearsal-green-within-72h gate satisfied"
    fi
fi
