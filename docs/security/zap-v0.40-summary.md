# ZAP Scan Summary — v0.40 (cross-tenant-isolation-audit, Issue #117)

**Run date:** 2026-04-16
**ZAP version:** 2.17.0 (`zaproxy/zap-stable:latest`)
**Target:** local dev stack (`http://localhost:8080` backend + `http://localhost:8081` nginx) brought up via `./dev-start.sh --nginx --observability`
**Operator:** Phase 5.5 of cross-tenant-isolation-audit (Marcus Webb's gate)

## Two-pass coverage

### Pass 1 — Baseline (passive scan + spider)

**Plan:** `zap-wrk/baseline-plan.yaml`
**Report:** `docs/security/zap-v0.40-baseline.{md,json}`

Standard ZAP baseline — spiders both ports + passive-scans every response. Catches security headers, info disclosure, common OWASP A1-A10 surface that a fresh deploy might regress.

| Risk | Count | Notes |
|---|---|---|
| **High** | **0** | ✓ |
| Medium | 1 | CSP `style-src 'unsafe-inline'` (4 instances) — pre-existing, from IBM Carbon Design System. **Accepted risk** per warroom 2026-04-16, see `docs/security/csp-policy.md` and tracking memory `project_csp_unsafe_inline_carbon_tracking.md` |
| Low | 0 | ✓ |
| Info | 1 | Modern Web Application detection (informational) |

### Pass 2 — Cross-tenant + SSRF (custom requestor plan)

**Plan template:** `zap-wrk/cross-tenant-plan.yaml.template`
**Wrapper:** `zap-wrk/run-cross-tenant-zap.sh` (captures JWT tokens via curl, substitutes UUIDs, runs ZAP)
**Report:** `docs/security/zap-v0.40-cross-tenant.{md,json}`

12 explicit probes through ZAP so its passive scanner inspects responses for info disclosure, reflected attacker input, and inconsistent error envelopes:

**Cross-tenant probes (8) — expect 404:**
1. `PUT /api/v1/tenants/{foreign}/oauth2-providers/{foreign}` (Phase 2.1)
2. `DELETE /api/v1/tenants/{foreign}/oauth2-providers/{foreign}` (Phase 2.1)
3. `POST /api/v1/api-keys/{foreign}/rotate` (Phase 2.2)
4. `DELETE /api/v1/api-keys/{foreign}` (Phase 2.2)
5. `DELETE /api/v1/subscriptions/{foreign}` (Phase 2.4)
6. `POST /api/v1/users/{foreign}/generate-access-code` (Phase 2.5)
7. `DELETE /api/v1/auth/totp/{foreign}` (Phase 2.3)
8. `POST /api/v1/auth/totp/{foreign}/regenerate-recovery-codes` (Phase 2.3)

**SSRF guard probes (4) — expect 400 (Phase 2.14):**
1. Cloud-metadata: `callbackUrl=http://169.254.169.254/latest/meta-data/iam/security-credentials/`
2. Loopback: `callbackUrl=http://127.0.0.1:9091/actuator/prometheus`
3. RFC1918: `callbackUrl=http://192.168.1.1/internal-admin`
4. Non-http scheme: `callbackUrl=file:///etc/passwd`

| Risk | Count |
|---|---|
| High | 0 |
| Medium | 0 |
| Low | 0 |
| Informational | 0 |

**100% of responses returned the expected 4xx code.** ZAP's passive scanner found nothing exploitable across all 12 probes — no reflected attacker input, no stack traces, no internal data leaked, no CSP violations on the error response shape.

## Cross-layer coverage matrix

The ZAP scans are one of seven layers verifying cross-tenant isolation:

| Layer | Coverage | Result |
|---|---|---|
| ZAP baseline (passive + spider) | localhost:8080 + :8081 | 0 High, 1 Medium (CSP, accept-risked) |
| **ZAP custom cross-tenant (12 probes)** | **5 admin surfaces + 4 SSRF cases** | **0 alerts** |
| Karate cross-tenant smoke | 14 scenarios (8 negative + 5 positive control + 1 ignored metric) | 14/14 green |
| Playwright cross-tenant smoke | 8 scenarios | 8/8 green |
| Backend integration tests | 21+ `tc_*_crossTenant_*` across 10 test files | 685/685 green |
| ArchUnit Family A + B | compile-time guard, strict | 4/4 rules pass |
| TenantPredicateCoverageTest | JSqlParser + JavaParser SQL static analysis | 3/3 pass |

## Reproduction

```bash
./dev-start.sh --nginx --observability
# wait for stack to be ready

# Pass 1 — baseline
MSYS_NO_PATHCONV=1 docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -v "$(pwd)/zap-wrk:/zap/wrk:rw" \
    -t zaproxy/zap-stable:latest \
    zap.sh -cmd -autorun /zap/wrk/baseline-plan.yaml

# Pass 2 — cross-tenant
bash zap-wrk/run-cross-tenant-zap.sh
```

Reports land in `zap-wrk/`. Move them to `docs/security/` to commit (the wrapper-substituted `zap-wrk/cross-tenant-plan.yaml` is `.gitignore`d because it contains real JWT tokens).

## Verdict — Phase 5.5 closeout

**Marcus Webb's OWASP ZAP-guided cross-tenant sweep: complete.**

- Baseline scan: clean except CSP exception (already accept-risked).
- Cross-tenant + SSRF scan: 0 findings.
- All 7 cross-tenant verification layers green.

**Recommendation:** ship v0.40.
