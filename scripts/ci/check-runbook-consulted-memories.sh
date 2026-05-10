#!/usr/bin/env bash
#
# check-runbook-consulted-memories.sh — ci-runbook-consulted-check guard.
#
# Enforces the `consulted:` frontmatter convention defined by
# `runbook-template-v1` (see docs/runbook-template.md §1). The companion
# skill `opsx-runbook-draft` pre-populates the block; this CI check raises
# the ceiling so even operators who skip the skill must satisfy three
# structural assertions before merge.
#
# Failure modes this catches:
#   1. Operator hand-authors a runbook without a `consulted:` block.
#   2. Operator commits a draft that still contains the
#      `> [!WARNING] generated draft` self-disclosure callout from the skill.
#   3. Operator adds a new feedback_*.md memory in the same PR but forgets
#      to cite it in the runbook's `consulted:` block. (No-op for projects
#      that don't commit memories to git; harmless when memories are
#      operator-laptop-only.)
#
# Usage (CI):
#   bash scripts/ci/check-runbook-consulted-memories.sh
#   Triggered by .github/workflows/runbook-consulted-check.yml on PRs that
#   touch docs/oracle-update-notes-v*.md.
#
# Usage (local pre-commit):
#   bash scripts/ci/check-runbook-consulted-memories.sh
#   Run from repo root before pushing a runbook PR. Same exit codes.
#
# Escape hatch:
#   PR title contains the literal substring `[skip-runbook-check]`.
#   Use ONLY for emergency hotfixes; abuse will be reviewed quarterly.
#
# Exit codes:
#   0 = no runbook touched, or all assertions pass, or skip token present
#   1 = at least one assertion failed
#   2 = environmental failure (git missing, repo state unexpected)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# ── Helpers ─────────────────────────────────────────────────────────────

# Marker text the opsx-runbook-draft skill emits in its [!WARNING] callout.
# Must stay in sync with .claude/skills/opsx-runbook-draft/SKILL.md.
readonly DRAFT_WARNING_MARKER='This draft is generated'

# Detect changed files. In CI use diff against origin/main; locally fall back
# to staged + unstaged + untracked so the check works pre-push.
detect_changed_files() {
    if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
        # GitHub Actions PR — diff base..HEAD
        git diff --name-only "origin/${GITHUB_BASE_REF}...HEAD" 2>/dev/null || true
    elif git rev-parse origin/main &>/dev/null; then
        git diff --name-only "origin/main...HEAD" 2>/dev/null || true
    else
        # Fallback: staged + unstaged + untracked (local pre-commit usage)
        {
            git diff --name-only HEAD 2>/dev/null
            git diff --name-only --cached 2>/dev/null
            git ls-files --others --exclude-standard 2>/dev/null
        } | sort -u
    fi
}

# Detect newly-ADDED files only (for assertion 3 — uncited new memory).
detect_added_files() {
    if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
        git diff --name-only --diff-filter=A "origin/${GITHUB_BASE_REF}...HEAD" 2>/dev/null || true
    elif git rev-parse origin/main &>/dev/null; then
        git diff --name-only --diff-filter=A "origin/main...HEAD" 2>/dev/null || true
    else
        git diff --name-only --diff-filter=A HEAD 2>/dev/null || true
        git diff --name-only --diff-filter=A --cached 2>/dev/null || true
        git ls-files --others --exclude-standard 2>/dev/null || true
    fi
}

pr_title_contains_skip_token() {
    local title="${GITHUB_PR_TITLE:-}"
    [[ "$title" == *"[skip-runbook-check]"* ]]
}

# ── Assertions ──────────────────────────────────────────────────────────

# Assertion 1: each touched runbook contains a `consulted:` block.
# Per template v1: the block is YAML-fenced and the `consulted:` key
# appears at column 0 within that fence.
check_consulted_block_present() {
    local runbook="$1"
    if ! grep -qE '^consulted:' "$runbook"; then
        echo "FAIL: $runbook is missing the 'consulted:' frontmatter block" >&2
        echo "      Per docs/runbook-template.md §1 (runbook-template-v1)," >&2
        echo "      every release runbook MUST list every memory file the author" >&2
        echo "      reviewed in a YAML-fenced 'consulted:' block." >&2
        echo "      Run /opsx-runbook-draft to auto-populate, then refine." >&2
        return 1
    fi
    return 0
}

# Assertion 2: no leftover [!WARNING] draft callout from the skill.
check_no_leftover_warning() {
    local runbook="$1"
    if grep -qF "$DRAFT_WARNING_MARKER" "$runbook"; then
        echo "FAIL: $runbook still contains the opsx-runbook-draft warning callout" >&2
        echo "      The 'This draft is generated' callout means the runbook is" >&2
        echo "      half-baked. Refine every citation + every gate, then" >&2
        echo "      remove the [!WARNING] block before merge." >&2
        return 1
    fi
    return 0
}

# Assertion 3: any newly-added feedback_*.md memory committed in this PR
# must be referenced in at least one touched runbook's body. No-op when
# memories are operator-laptop-only (typical for FABT today).
check_new_memories_cited() {
    local runbook="$1"
    local fail=0
    while IFS= read -r added; do
        [[ -z "$added" ]] && continue
        local basename
        basename="$(basename "$added")"
        # Match the memory's filename (with or without .md) inside the runbook
        if ! grep -qE "${basename%.md}|${basename}" "$runbook"; then
            echo "FAIL: new memory $added is not cited in $runbook" >&2
            echo "      A PR that adds a new feedback memory AND touches a runbook" >&2
            echo "      must cite the new memory in the runbook's 'consulted:' block" >&2
            echo "      (or 'not-applicable:' with a reason). Otherwise the lesson" >&2
            echo "      lands in memory but never enters a runbook — defeating the" >&2
            echo "      runbook-template-v1 purpose." >&2
            fail=1
        fi
    done < <(detect_added_files | grep -E '^.*memory/.*feedback_.*\.md$' || true)
    return $fail
}

# ── Main flow ───────────────────────────────────────────────────────────

CHANGED=$(detect_changed_files)
RUNBOOKS=$(echo "$CHANGED" | grep -E '^docs/oracle-update-notes-v[0-9]+\.[0-9]+\.[0-9]+\.md$' || true)

if [[ -z "$RUNBOOKS" ]]; then
    echo "ok: no runbook touched in this changeset; check is a no-op"
    exit 0
fi

if pr_title_contains_skip_token; then
    echo "WARN: PR title contains [skip-runbook-check] — skipping consulted-memory assertions"
    echo "      Affected runbooks: $RUNBOOKS"
    echo "      Use of this escape hatch is reviewed quarterly."
    exit 0
fi

FAIL=0
COUNT=0
while IFS= read -r runbook; do
    [[ -z "$runbook" ]] && continue
    COUNT=$((COUNT + 1))

    check_consulted_block_present "$runbook" || FAIL=1
    check_no_leftover_warning "$runbook" || FAIL=1
    check_new_memories_cited "$runbook" || FAIL=1
done <<< "$RUNBOOKS"

if [[ $FAIL -eq 0 ]]; then
    echo "ok: all $COUNT touched runbook(s) satisfy runbook-template-v1 'consulted:' convention"
    exit 0
else
    echo ""
    echo "FAIL: at least one runbook assertion failed; see messages above" >&2
    echo "      Reference: docs/runbook-template.md §1" >&2
    echo "      Skill:     /opsx-runbook-draft (pre-populates the block)" >&2
    echo "      Skip:      add [skip-runbook-check] to PR title (emergency only)" >&2
    exit 1
fi
