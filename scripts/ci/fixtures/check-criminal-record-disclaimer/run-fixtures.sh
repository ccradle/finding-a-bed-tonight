#!/usr/bin/env bash
#
# run-fixtures.sh — drives the check-criminal-record-disclaimer guard
# against the fixtures in this directory and asserts the expected
# pass/fail outcome for each. Run from the repo root or anywhere; the
# script resolves paths relative to its own location.
#
# Exit codes:
#   0 = all fixtures had the expected outcome
#   1 = at least one fixture deviated (regression)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/../../check-criminal-record-disclaimer-co-rendering.sh"

if [[ ! -x "$GUARD" ]]; then
    chmod +x "$GUARD" || true
fi

FIXTURES=(
    "fail-raw-prop-no-disclaimer.tsx:1"
    "pass-raw-prop-with-disclaimer.tsx:0"
    "pass-jsdoc-only-mention.tsx:0"
    "pass-inline-comment-mention.tsx:0"
    "pass-block-comment-inline.tsx:0"
)

failures=0
for entry in "${FIXTURES[@]}"; do
    fixture="${entry%:*}"
    expected="${entry##*:}"
    fixture_path="$SCRIPT_DIR/$fixture"

    actual=0
    "$GUARD" "$fixture_path" >/dev/null 2>&1 || actual=$?

    if [[ "$actual" -eq "$expected" ]]; then
        echo "✓ $fixture (expected exit=$expected, got $actual)"
    else
        echo "✗ $fixture (expected exit=$expected, got $actual)"
        failures=$((failures + 1))
    fi
done

if (( failures > 0 )); then
    echo ""
    echo "FAIL: $failures fixture(s) deviated from expected behavior."
    echo "If this is intentional, update the FIXTURES list in this script."
    exit 1
fi

echo ""
echo "All ${#FIXTURES[@]} fixtures matched expected behavior."
