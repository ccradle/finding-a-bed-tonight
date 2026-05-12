#!/usr/bin/env bash
#
# check-feedback-link-discipline.sh — issue-reporting-feedback §8 CI guard.
#
# Enforces two invariants on the in-app feedback-link surface:
#
#   1. Every `target="_blank"` in `frontend/src/**/*.tsx` is paired with
#      `rel="noopener noreferrer"` within the SAME JSX opening element.
#      Without rel=noopener the opened tab can navigate the opener
#      (window.opener.location = ...) — a known phishing vector. Modern
#      browsers default-apply noopener, but the explicit attribute is
#      project policy for defense-in-depth.
#
#   2. New `https://github.com/.../issues/new?...` URLs MUST be
#      constructed from the allowlisted `buildReportProblemUrl` helper
#      in `ReportProblemLink.tsx` — NOT inline-strings scattered across
#      the frontend. The allowlist guarantees URL pre-fill values are
#      compile-time constants + the version-derived `fabt_version`
#      param (warroom round 1 B1: prevents PII leak into a public
#      GitHub issue body; warroom H1: prevents label-injection via
#      `?labels=spam,wontfix,duplicate` shared via a hostile deep-link).
#
# Implementation: delegates to a tiny Python script — JSX elements span
# multiple lines and bash/grep multi-line handling is brittle. Python is
# available on every Ubuntu CI runner; on Windows Git Bash use `python`
# from the PATH (Vite already requires Node, so a dev box has Python too
# via the dev-prereqs runbook).
#
# Usage (CI):
#   bash scripts/ci/check-feedback-link-discipline.sh
#   Wired into `.github/workflows/ci.yml` as a fast lint step.
#
# Usage (local pre-commit):
#   bash scripts/ci/check-feedback-link-discipline.sh
#   Run from repo root before pushing a frontend change touching links.
#
# Exit codes:
#   0 = all assertions pass
#   1 = at least one assertion failed
#   2 = environmental failure (frontend/src missing or python missing)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

FRONTEND_SRC="frontend/src"

if [[ ! -d "$FRONTEND_SRC" ]]; then
    echo "FAIL (exit 2): $FRONTEND_SRC not found — check is running from wrong cwd" >&2
    exit 2
fi

# Find python interpreter. Prefer `python3` (Ubuntu default), fall back
# to `python` (Git Bash on Windows often only has `python`).
PYTHON=""
if command -v python3 >/dev/null 2>&1; then
    PYTHON="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON="python"
else
    echo "FAIL (exit 2): neither python3 nor python found on PATH" >&2
    exit 2
fi

exec "$PYTHON" - "$FRONTEND_SRC" <<'PYEOF'
"""Multi-line-aware feedback-link-discipline checks."""
import re
import sys
from pathlib import Path

FRONTEND_SRC = Path(sys.argv[1])
# Forward-slash POSIX path for cross-platform comparison.
ALLOWLIST_FILE_POSIX = "frontend/src/components/ReportProblemLink.tsx"

# Match a target="_blank" (either string or JSX expression). Used as
# the anchor to locate JSX elements that need rel=noopener nearby.
TARGET_BLANK_RE = re.compile(
    r"""target=(?:"_blank"|'_blank'|\{[^}]*['"]_blank['"][^}]*\})"""
)
REL_NOOPENER_RE = re.compile(
    r"""rel=(?:"[^"]*noopener[^"]*"|'[^']*noopener[^']*'|\{[^}]*noopener[^}]*\})"""
)
GH_ISSUE_URL_RE = re.compile(
    r"https://github\.com/ccradle/finding-a-bed-tonight/issues/new"
)

errors = []

for tsx_file in sorted(FRONTEND_SRC.rglob("*.tsx")):
    file_posix = tsx_file.as_posix()
    text = tsx_file.read_text(encoding="utf-8", errors="replace")

    # Assertion 1: each target="_blank" must have rel=noopener in the
    # same JSX opening element. We define "same JSX element" as the
    # text between the most recent unescaped '<' before the match and
    # the next unescaped '>' after — JSX element bounds.
    for m in TARGET_BLANK_RE.finditer(text):
        # Locate JSX element start: nearest preceding '<' that is not
        # part of a JS comparison or string. Heuristic: scan back to
        # the most recent '<' that is followed by a JSX identifier
        # character (letter, $ or _). Good enough for our codebase.
        start = m.start()
        # Walk back to find the most recent '<' not in a string.
        i = start - 1
        depth_brace = 0
        while i >= 0:
            ch = text[i]
            if ch == "}":
                depth_brace += 1
            elif ch == "{":
                depth_brace -= 1
            elif ch == "<" and depth_brace == 0:
                # Make sure this is a JSX open tag (next char is
                # a tag-name character) not a JS comparison.
                if i + 1 < len(text) and (text[i + 1].isalpha() or text[i + 1] in "_$"):
                    break
            i -= 1
        if i < 0:
            i = max(0, start - 400)  # safety net

        # Walk forward to find the closing '>' of the opening tag.
        j = m.end()
        depth_brace = 0
        while j < len(text):
            ch = text[j]
            if ch == "{":
                depth_brace += 1
            elif ch == "}":
                depth_brace -= 1
            elif ch == ">" and depth_brace == 0:
                break
            j += 1
        if j >= len(text):
            j = min(len(text), m.end() + 400)

        element_text = text[i:j + 1]
        if not REL_NOOPENER_RE.search(element_text):
            line_no = text[:m.start()].count("\n") + 1
            errors.append(
                f"{file_posix}:{line_no}: target=\"_blank\" without "
                f"paired rel=\"noopener noreferrer\" in same JSX element"
            )

    # Assertion 2: GH issue URLs only allowed in ReportProblemLink.tsx.
    if file_posix.endswith(ALLOWLIST_FILE_POSIX) or file_posix == ALLOWLIST_FILE_POSIX:
        continue
    for m in GH_ISSUE_URL_RE.finditer(text):
        line_no = text[:m.start()].count("\n") + 1
        errors.append(
            f"{file_posix}:{line_no}: inline GitHub issue URL — must use "
            f"buildReportProblemUrl() from {ALLOWLIST_FILE_POSIX}"
        )

if errors:
    for e in errors:
        print(f"FAIL: {e}", file=sys.stderr)
    print("", file=sys.stderr)
    print(
        "FAIL: feedback-link-discipline check failed; see messages above. "
        "Reference: openspec/changes/issue-reporting-feedback/design.md "
        "§2 + §7 + §8.",
        file=sys.stderr,
    )
    sys.exit(1)

print("ok: all feedback-link-discipline assertions pass")
sys.exit(0)
PYEOF
