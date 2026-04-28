#!/bin/bash
# Verify Rollup tree-shakes the platform-operator chunk when
# VITE_PLATFORM_UI_ENABLED is unset/false.
#
# Why: App.tsx wraps the lazy import in a top-level
# `if (import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true')` so the
# dynamic `import()` literal is dead-code-eliminated when the flag is
# missing. If a future change moves the env-var check INSIDE the lazy
# callback (e.g., `lazy(() => import.meta.env.X === 'true' ? import(...)
# : Promise.resolve(...))`), Rollup can no longer eliminate the import
# and the chunk silently ships in flag-false builds — breaking the
# rollback story. This script catches that regression at CI time.
#
# Run from frontend/: `bash scripts/verify-platform-chunk-tree-shake.sh`
# Exits 0 on success, 1 on regression.

set -euo pipefail

cd "$(dirname "$0")/.."

echo "Building frontend with VITE_PLATFORM_UI_ENABLED unset..."
unset VITE_PLATFORM_UI_ENABLED
npm run build > /tmp/build-flag-off.log 2>&1

# Match ANY platform-* chunk (Placeholder, Layout, Login, MfaEnroll,
# MfaVerify, Dashboard, helpers, etc.). Vite/Rollup names chunks by the
# basename of their lazy-import source file; case-insensitive `Platform*`
# catches all of them. The earlier pattern only checked the lowercased
# placeholder bundle name and would have silently passed a regression
# that emitted PlatformLogin / PlatformMfaEnroll chunks. Caught by
# warroom round 6 J1.
PLATFORM_CHUNKS=$(find dist/assets -iname 'Platform*.js' ! -name '*.map' 2>/dev/null | wc -l | tr -d ' ')

if [ "$PLATFORM_CHUNKS" != "0" ]; then
  echo "REGRESSION: build emitted $PLATFORM_CHUNKS platform chunk(s) when flag was unset:"
  find dist/assets -iname 'Platform*.js' ! -name '*.map' 2>/dev/null
  echo ""
  echo "  The top-level guard in App.tsx must look like:"
  echo "    const PlatformPlaceholder ="
  echo "      import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'"
  echo "        ? lazy(() => import('./pages/platform/PlatformPlaceholder'))"
  echo "        : null;"
  echo ""
  echo "  If the env-var check is INSIDE lazy(), Rollup cannot eliminate."
  exit 1
fi

echo "Tree-shake confirmed: no platform chunks emitted with flag unset."

echo "Building with VITE_PLATFORM_UI_ENABLED=true to confirm chunks WOULD ship..."
VITE_PLATFORM_UI_ENABLED=true npm run build > /tmp/build-flag-on.log 2>&1

PLATFORM_CHUNKS_ON=$(find dist/assets -iname 'Platform*.js' ! -name '*.map' 2>/dev/null | wc -l | tr -d ' ')

# Slice C ships at minimum 5 chunks: Layout, Login, MfaEnroll, MfaVerify,
# Placeholder. (Plus possibly a shared platformApi chunk that Vite splits
# out automatically.) Future slices may add Dashboard etc; a "≥5" floor
# is the right invariant here, not equality.
MIN_EXPECTED_CHUNKS=5
if [ "$PLATFORM_CHUNKS_ON" -lt "$MIN_EXPECTED_CHUNKS" ]; then
  echo "REGRESSION: flag=true build emitted only $PLATFORM_CHUNKS_ON platform chunk(s)"
  echo "  (expected at least $MIN_EXPECTED_CHUNKS — Layout, Login, MfaEnroll, MfaVerify, Placeholder)."
  echo "  Either a lazy import was deleted, its module path broke, or"
  echo "  Rollup is over-aggressively merging chunks. Inspect dist/assets/:"
  find dist/assets -iname 'Platform*.js' ! -name '*.map' 2>/dev/null
  exit 1
fi

echo "flag=true build emits $PLATFORM_CHUNKS_ON platform chunk(s) (expected ≥$MIN_EXPECTED_CHUNKS)."
echo ""
echo "Platform-chunk tree-shake CI assertion: PASS"
