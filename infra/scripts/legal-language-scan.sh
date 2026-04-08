#!/usr/bin/env bash
#
# legal-language-scan.sh — Scan for overclaimed compliance language
#
# Flags keywords that require human review: bare compliance claims,
# absolute guarantees, and commercial product equivalence claims.
# Supports an allowlist file for known-good matches.
#
# Usage:
#   ./infra/scripts/legal-language-scan.sh [directory]
#   Default directory: current working directory
#
# Allowlist:
#   Create .legal-allowlist in the scan directory. One pattern per line.
#   Lines matching any allowlist pattern are excluded from results.
#   Comments (#) and blank lines are ignored.
#
# Exit codes:
#   0 — no unallowlisted matches found
#   1 — matches found requiring review
#
set -euo pipefail

SCAN_DIR="${1:-.}"
ALLOWLIST="${SCAN_DIR}/.legal-allowlist"

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

# Keywords that require human review when found in documentation or code.
# Each pattern is a case-insensitive extended regex.
PATTERNS=(
  '\bcompliant\b'
  '\bcertified\b'
  '\bguarantees\b'
  '\bensures compliance\b'
  '\bzero downtime\b'
  '\bno way to\b'
  '\bequivalent to\b'
  '\b100% uptime\b'
  '\benterprise-ready\b'
  '\bproduction-grade\b'
)

# File extensions to scan
INCLUDE_ARGS=(
  --include="*.md"
  --include="*.tsx"
  --include="*.ts"
  --include="*.java"
  --include="*.html"
  --include="*.yml"
  --include="*.yaml"
)

# Directories to exclude
EXCLUDE_ARGS=(
  --exclude-dir=node_modules
  --exclude-dir=target
  --exclude-dir=dist
  --exclude-dir=.git
  --exclude-dir=test-results
  --exclude-dir=.next
)

# Load allowlist patterns
ALLOWLIST_PATTERNS=()
if [[ -f "$ALLOWLIST" ]]; then
  while IFS= read -r line; do
    # Skip comments and blank lines
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    ALLOWLIST_PATTERNS+=("$line")
  done < "$ALLOWLIST"
fi

# Check if a match line is allowlisted
is_allowlisted() {
  local match_line="$1"
  for pattern in "${ALLOWLIST_PATTERNS[@]+"${ALLOWLIST_PATTERNS[@]}"}"; do
    if [[ "$match_line" == *"$pattern"* ]]; then
      return 0
    fi
  done
  return 1
}

FOUND_ISSUES=0
TOTAL_MATCHES=0
ALLOWLISTED_MATCHES=0

echo ""
echo -e "${YELLOW}Legal Language Scan${NC}"
echo -e "${YELLOW}==================${NC}"
echo "Scanning: $SCAN_DIR"
[[ -f "$ALLOWLIST" ]] && echo "Allowlist: $ALLOWLIST (${#ALLOWLIST_PATTERNS[@]} patterns)" || echo "Allowlist: none"
echo ""

for pattern in "${PATTERNS[@]}"; do
  MATCHES=$(grep -rn -i -E "$pattern" "${INCLUDE_ARGS[@]}" "${EXCLUDE_ARGS[@]}" "$SCAN_DIR" 2>/dev/null || true)

  if [[ -n "$MATCHES" ]]; then
    # Capture pattern in local var to prevent loop variable mutation
    local_pattern="$pattern"
    while IFS= read -r match_line; do
      TOTAL_MATCHES=$((TOTAL_MATCHES + 1))
      if is_allowlisted "$match_line"; then
        ALLOWLISTED_MATCHES=$((ALLOWLISTED_MATCHES + 1))
      else
        if [[ $FOUND_ISSUES -eq 0 ]]; then
          echo -e "${RED}Issues found:${NC}"
          echo ""
        fi
        FOUND_ISSUES=$((FOUND_ISSUES + 1))
        echo -e "  ${RED}[${local_pattern}]${NC} $match_line"
      fi
    done <<< "$MATCHES"
  fi
done

echo ""
echo "---"
echo "Total matches: $TOTAL_MATCHES"
echo "Allowlisted:   $ALLOWLISTED_MATCHES"
echo "Flagged:       $FOUND_ISSUES"
echo ""

if [[ $FOUND_ISSUES -gt 0 ]]; then
  echo -e "${RED}⚠  $FOUND_ISSUES match(es) require review.${NC}"
  echo "Use 'designed to support' instead of 'compliant'."
  echo "Add false positives to .legal-allowlist."
  exit 1
else
  echo -e "${GREEN}✅ No overclaimed compliance language found.${NC}"
  exit 0
fi
