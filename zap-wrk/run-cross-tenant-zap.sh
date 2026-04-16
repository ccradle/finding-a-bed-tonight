#!/usr/bin/env bash
# Run ZAP cross-tenant-audit scan with multi-angle coverage:
#   Angle 1: 8 cross-tenant probes against the 5 Phase 2 admin surfaces
#   Angle 2: 4 SSRF guard probes (cloud-metadata, loopback, RFC1918, file://)
#   Angle 3: passive scan of all responses (info disclosure, reflected input)
#
# JWT tokens captured via curl pre-flight; substituted into the YAML
# template; ZAP runs in -cmd -autorun mode.
set -euo pipefail

cd "$(dirname "$0")"

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
TENANT_SLUG="${TENANT_SLUG:-dev-coc}"

echo "[zap] Capturing admin token..."
ADMIN_TOKEN=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"tenantSlug\":\"$TENANT_SLUG\",\"email\":\"admin@dev.fabt.org\",\"password\":\"admin123\"}" \
    | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

echo "[zap] Capturing cocadmin token..."
COCADMIN_TOKEN=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"tenantSlug\":\"$TENANT_SLUG\",\"email\":\"cocadmin@dev.fabt.org\",\"password\":\"admin123\"}" \
    | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

echo "[zap] Substituting tokens + 10 random UUIDs into plan template..."
SUBSTITUTED_PLAN=cross-tenant-plan.yaml
cp cross-tenant-plan.yaml.template "$SUBSTITUTED_PLAN"

# 10 random UUIDs for the foreign-UUID slots
for i in 1 2 3 4 5 6 7 8 9 10; do
    UUID=$(python -c "import uuid;print(uuid.uuid4())")
    # Use ~ as sed delimiter to avoid escaping UUID dashes
    sed -i.bak "s~__RANDOM_UUID_${i}__~${UUID}~g" "$SUBSTITUTED_PLAN"
done

# Token substitution last (tokens are large; do them after UUIDs)
sed -i.bak "s~__ADMIN_TOKEN__~${ADMIN_TOKEN}~g" "$SUBSTITUTED_PLAN"
sed -i.bak "s~__COCADMIN_TOKEN__~${COCADMIN_TOKEN}~g" "$SUBSTITUTED_PLAN"
rm -f "${SUBSTITUTED_PLAN}.bak"

echo "[zap] Running ZAP automation..."
MSYS_NO_PATHCONV=1 docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -v "$(pwd):/zap/wrk:rw" \
    -t zaproxy/zap-stable:latest \
    zap.sh -cmd -autorun /zap/wrk/cross-tenant-plan.yaml

echo "[zap] Done. Reports:"
ls -la zap-v0.40-cross-tenant.{md,json} 2>/dev/null || echo "(reports not generated — check ZAP output above)"
