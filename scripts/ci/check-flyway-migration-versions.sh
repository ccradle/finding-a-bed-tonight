#!/usr/bin/env bash
#
# check-flyway-migration-versions.sh — v0.45.0 Flyway HWM guard.
#
# Enforces the renumber-forward posture chosen in the Phase B close-out
# warroom (#151): every NEW Flyway migration file added in this branch
# must have version strictly greater than prod's current applied HWM
# (committed in deploy/prod-state.json).
#
# The rule exists because prod's flyway_schema_history has a permanently
# out-of-order installed_rank sequence (V74 was applied at v0.42.1, then
# V67-V72 at v0.43.1, then V73 at v0.44.1) and lives with that history
# until the Flyway B-baseline reset at ~v0.60 (see
# docs/runbook.md -> 'Flyway Out-of-Order Posture'). Adding a new
# migration below the HWM would require SPRING_FLYWAY_OUT_OF_ORDER=true
# to apply, which is the temporary bridge we are retiring.
#
# Usage (local):
#   BASE_REF=origin/main ./scripts/ci/check-flyway-migration-versions.sh
#
# Usage (CI):
#   Runs against the PR base branch automatically.
#
# Exit codes:
#   0 = all new migrations are above HWM (or no new migrations)
#   1 = at least one new migration violates the HWM rule
#   2 = environmental failure (jq missing, deploy/prod-state.json missing)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

SNAPSHOT=deploy/prod-state.json
if [[ ! -f "$SNAPSHOT" ]]; then
    echo "FAIL: $SNAPSHOT not found. Cannot determine HWM." >&2
    exit 2
fi

# Parse HWM without adding a jq dependency on Windows / GitHub runners.
# The field is written as: "appliedMigrationsHighWaterMark": 74
HWM=$(sed -nE 's/.*"appliedMigrationsHighWaterMark"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' "$SNAPSHOT" | head -n1)
if ! [[ "$HWM" =~ ^[0-9]+$ ]]; then
    echo "FAIL: appliedMigrationsHighWaterMark in $SNAPSHOT is not an integer: '$HWM'" >&2
    exit 2
fi

BASE_REF="${BASE_REF:-origin/main}"
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    # In shallow-cloned CI builds, BASE_REF might not exist. Fetch it.
    git fetch --no-tags --depth=50 origin "${BASE_REF#origin/}" 2>/dev/null || true
fi

MIGRATION_GLOBS=(
    'backend/src/main/resources/db/migration/V*.sql'
    'backend/src/main/java/db/migration/V*.java'
)

# Collect newly-added migration files since BASE_REF. If BASE_REF still
# isn't resolvable (first commit on a branch), fall back to checking all
# migrations — strictly stricter than needed, but safe.
if git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    NEW_FILES=$(git diff --name-only --diff-filter=A "$BASE_REF...HEAD" -- "${MIGRATION_GLOBS[@]}" 2>/dev/null || true)
else
    echo "WARN: $BASE_REF unresolvable; scanning all repo migrations." >&2
    NEW_FILES=$(printf '%s\n' "${MIGRATION_GLOBS[@]}" | xargs -I{} sh -c 'ls {} 2>/dev/null' || true)
fi

if [[ -z "$NEW_FILES" ]]; then
    echo "✓ No new Flyway migrations in this diff (HWM = $HWM)."
    exit 0
fi

VIOLATIONS=()
for file in $NEW_FILES; do
    basename=$(basename "$file")
    # Match V<digits>[_<digits>]__rest
    version=$(printf '%s\n' "$basename" | sed -nE 's/^V([0-9]+)(_[0-9]+)?__.*$/\1/p')
    if [[ -z "$version" ]]; then
        echo "WARN: cannot parse Flyway version from $basename — skipping" >&2
        continue
    fi
    if (( version <= HWM )); then
        VIOLATIONS+=("$basename (version $version)")
    fi
done

if (( ${#VIOLATIONS[@]} > 0 )); then
    echo "FAIL: ${#VIOLATIONS[@]} new migration(s) below HWM ($HWM):"
    for v in "${VIOLATIONS[@]}"; do
        echo "    - $v"
    done
    cat <<EOF

Per the v0.45.0 Flyway renumber-forward posture, new migrations MUST
have version strictly greater than the prod HWM in deploy/prod-state.json.
This avoids the SPRING_FLYWAY_OUT_OF_ORDER bridge compose overlay on the
VM. Background: docs/runbook.md -> 'Flyway Out-of-Order Posture'.

Fix: rename the listed file(s) to a version greater than $HWM and update
any references in tests / migration-list docs.
EOF
    exit 1
fi

COUNT=$(printf '%s\n' "$NEW_FILES" | wc -l | tr -d ' ')
echo "✓ $COUNT new migration(s) all above HWM ($HWM)."
