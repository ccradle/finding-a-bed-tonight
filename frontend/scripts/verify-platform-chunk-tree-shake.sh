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

PLATFORM_CHUNKS=$(find dist/assets -name 'PlatformPlaceholder-*.js' -o -name 'platform-*.js' 2>/dev/null | wc -l | tr -d ' ')

if [ "$PLATFORM_CHUNKS" != "0" ]; then
  echo "REGRESSION: build emitted $PLATFORM_CHUNKS platform chunk(s) when flag was unset:"
  find dist/assets -name 'PlatformPlaceholder-*.js' -o -name 'platform-*.js' 2>/dev/null
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

echo "Building with VITE_PLATFORM_UI_ENABLED=true to confirm chunk WOULD ship..."
VITE_PLATFORM_UI_ENABLED=true npm run build > /tmp/build-flag-on.log 2>&1

PLATFORM_CHUNKS_ON=$(find dist/assets -name 'PlatformPlaceholder-*.js' 2>/dev/null | wc -l | tr -d ' ')

if [ "$PLATFORM_CHUNKS_ON" = "0" ]; then
  echo "REGRESSION: flag=true build did NOT emit a platform chunk."
  echo "  Either the lazy import was deleted or its module path is broken."
  exit 1
fi

echo "flag=true build emits $PLATFORM_CHUNKS_ON platform chunk(s) (expected)."
echo ""
echo "Platform-chunk tree-shake CI assertion: PASS"
