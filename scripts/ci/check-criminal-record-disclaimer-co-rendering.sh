#!/usr/bin/env bash
#
# check-criminal-record-disclaimer-co-rendering.sh — transitional-reentry-support task 7.3.
#
# Enforces design D6: any frontend .tsx/.jsx file that references a
# `criminal_record_policy` field (also `accepts_felonies` or
# `excluded_offense_types`) in non-comment code MUST also render
# `<CriminalRecordPolicyDisclaimer>`. Internal legal review concluded
# that the disclaimer is load-bearing for navigator UX; the
# co-rendering rule is the only mechanism that prevents a future
# refactor from drifting the data + disclaimer apart.
#
# Comment-line filter (warroom H3, 2026-04-29):
#   The original spec wording — `grep -vE '^\s*(\*|//|/\*)'` — only
#   filtered lines that BEGIN with comment markers. A line like
#       const x = data.criminal_record_policy; // load policy
#   would slip through (`//` not at column 0). Tightened approach:
#     1. Strip inline single-line `/* ... */` blocks.
#     2. Strip `// ...` to end of line.
#     3. Drop lines that begin with `*` (JSDoc body) or `/*` (block opener).
#   Multi-line `/* ... \n ... */` block comments where each interior
#   line starts with `*` are caught by step 3. Multi-line block
#   comments without leading `*` are an unusual style and not handled
#   — fixture `pass-block-comment-inline.tsx` demonstrates the
#   single-line case is covered.
#
# Usage:
#   ./scripts/ci/check-criminal-record-disclaimer-co-rendering.sh
#       (no args — scans frontend/src/**/*.{tsx,jsx})
#   ./scripts/ci/check-criminal-record-disclaimer-co-rendering.sh path/to/file.tsx
#       (file argument — scans only that file; used by the fixture runner)
#
# Exit codes:
#   0 = no violations found
#   1 = at least one file references the tokens without rendering the disclaimer
#   2 = environmental failure (scan dir missing, no candidate files)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

TOKENS_RE='criminal_record_policy|accepts_felonies|excluded_offense_types'
DISCLAIMER='<CriminalRecordPolicyDisclaimer'

# Strip TypeScript/JSX comments for the purpose of token detection.
# Stdin → stdout. Does NOT mutate files on disk.
strip_comments() {
    # 1. Inline /* ... */ on a single line — replace with empty.
    # 2. Trailing // ... — replace with empty.
    # 3. Drop lines that begin with whitespace + (`*` or `/*`) — JSDoc
    #    body lines and block-comment openers.
    sed -E 's,/\*[^*]*\*/,,g; s,//.*$,,' \
        | grep -vE '^[[:space:]]*(\*|/\*)'
}

# Inspect ONE file; echo the file path if it violates, return non-zero.
inspect_file() {
    local file="$1"
    if [[ ! -f "$file" ]]; then
        echo "WARN: file not found: $file" >&2
        return 0
    fi
    local stripped
    # `|| true` because grep -vE returns 1 on no matches; the pipe success
    # depends on the final filter producing output, not on intermediate
    # exit codes.
    stripped=$(strip_comments < "$file" || true)

    if echo "$stripped" | grep -qE "$TOKENS_RE"; then
        # File mentions a token in non-comment code. Now verify the
        # disclaimer renders too — same stripped content (so a comment-
        # only mention of `<CriminalRecordPolicyDisclaimer` doesn't
        # accidentally satisfy the requirement).
        if ! echo "$stripped" | grep -qF "$DISCLAIMER"; then
            echo "$file"
            return 1
        fi
    fi
    return 0
}

if [[ $# -ge 1 ]]; then
    # File-argument mode (used by the fixture runner).
    if inspect_file "$1"; then
        echo "✓ $1: clean"
        exit 0
    else
        echo "FAIL: $1 references criminal_record_policy data without rendering <CriminalRecordPolicyDisclaimer>"
        exit 1
    fi
fi

# Default mode: scan frontend/src/**/*.{tsx,jsx}.
SCAN_DIR="frontend/src"
if [[ ! -d "$SCAN_DIR" ]]; then
    echo "FAIL: $SCAN_DIR not found from $(pwd)" >&2
    exit 2
fi

# `mapfile` reads find output into an array; using -print0 + null-delim
# would be safer for spaces-in-paths but find output here is project-
# controlled and known to be space-free.
mapfile -t CANDIDATES < <(find "$SCAN_DIR" -type f \( -name '*.tsx' -o -name '*.jsx' \))

if (( ${#CANDIDATES[@]} == 0 )); then
    echo "WARN: $SCAN_DIR contains no .tsx/.jsx files — nothing to check" >&2
    exit 0
fi

VIOLATIONS=()
for file in "${CANDIDATES[@]}"; do
    if ! offender=$(inspect_file "$file"); then
        VIOLATIONS+=("$offender")
    fi
done

if (( ${#VIOLATIONS[@]} > 0 )); then
    echo "FAIL: ${#VIOLATIONS[@]} file(s) reference criminal_record_policy data without rendering <CriminalRecordPolicyDisclaimer>:"
    for v in "${VIOLATIONS[@]}"; do
        echo "  - $v"
    done
    cat <<EOF

Per design D6 (transitional-reentry-support / internal legal review
2026-04-28), any UI surface that displays criminal_record_policy
data MUST co-render <CriminalRecordPolicyDisclaimer>. Import:

    import { CriminalRecordPolicyDisclaimer } from '../components/CriminalRecordPolicyDisclaimer';

Then render adjacent to the data:

    <CriminalRecordPolicyDisclaimer vawaProtectionsApply={shelter.vawaProtectionsApply} />

The disclaimer text is legal-reviewed; it must be the dedicated component,
not a custom paragraph copy-paste.
EOF
    exit 1
fi

echo "✓ ${#CANDIDATES[@]} file(s) scanned; all criminal_record_policy references co-render the disclaimer."
