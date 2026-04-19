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
# Usage:
#   ./scripts/ci/verify-release-gate-pins.sh
#
# Exit codes:
#   0 = all pins match actual file SHA-256
#   1 = at least one pin mismatch
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
