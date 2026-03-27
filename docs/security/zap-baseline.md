# OWASP ZAP Security Scan Baseline

**Date:** 2026-03-27
**Version:** v0.14.1 + security-hardening-pre-pilot branch
**Scanner:** OWASP ZAP (zaproxy/zap-stable Docker image)
**Scan type:** API scan via OpenAPI spec
**Target:** `http://localhost:8080/api/v1/api-docs` (local development environment, HTTP)
**Scan scope:** Application-level vulnerabilities only

## Important Limitations

This scan was run against a **local development environment without TLS**. It covers application-level vulnerabilities (injection, XSS, access control, information disclosure) but does NOT cover:

- TLS configuration (cipher suites, certificate validation, HSTS behavior)
- Reverse proxy hardening (nginx production configuration)
- Infrastructure-level security (firewall rules, network segmentation)
- Authenticated endpoint scanning (requires ZAP authentication context configuration)

A full scan against the deployed demo/production environment should be performed before any city IT engagement.

## Results Summary

| Category | Count |
|----------|-------|
| PASS | 116 |
| FAIL (HIGH/CRITICAL) | 0 |
| WARN | 2 |
| INFO | 0 |

## Warnings (Triaged)

### WARN: Source Code Disclosure - SQL [10099]

**URL:** `http://localhost:8080/api/v1/api-docs`
**Assessment:** False positive. ZAP detected SQL-like keywords in the OpenAPI specification document, which describes database-related API endpoints. The spec itself is a documentation artifact, not executable code.
**Action:** No fix needed. Suppress in future scans via ZAP config file.

### WARN: Cross-Origin-Resource-Policy Header Missing [90004]

**URL:** `http://localhost:8080/api/v1/api-docs`
**Assessment:** Low risk. The CORP header defends against Spectre-style cross-origin data extraction. Relevant when browsers load API resources cross-origin. The API is accessed via same-origin requests from the React frontend or via authenticated API clients.
**Action:** Consider adding `Cross-Origin-Resource-Policy: same-origin` to security headers in a future update. Not blocking for pilot.

## Notable Passes

The following high-value security checks all passed:

- **SQL Injection** (all variants: MySQL, PostgreSQL, Oracle, MSSQL, time-based) — PASS
- **Cross Site Scripting** (reflected, persistent, DOM-based) — PASS
- **Path Traversal** — PASS
- **Remote Code Execution** (Shell Shock, Log4Shell, Spring4Shell, React2Shell) — PASS
- **Remote OS Command Injection** (direct and time-based) — PASS
- **CRLF Injection** — PASS
- **XML External Entity Attack** — PASS
- **Server Side Template Injection** (direct and blind) — PASS
- **Spring Actuator Information Leak** — PASS
- **Hidden File Finder** (.env, .htaccess) — PASS
- **PII Disclosure** — PASS
- **Information Disclosure - Debug Error Messages** — PASS

## How to Reproduce

```bash
# Start the backend (dev-start.sh --observability)
# Then run:
docker run --rm -v "$(pwd -W):/zap/wrk/:rw" \
  zaproxy/zap-stable zap-api-scan.py \
  -t http://host.docker.internal:8080/api/v1/api-docs \
  -f openapi \
  -r zap-api-report.html \
  -J zap-api-report.json \
  -w zap-api-report.md
```

Exit codes: 0 = all pass, 1 = failures, 2 = warnings only, 3 = errors.

## Full Reports

- `zap-api-report.html` — detailed HTML report with request/response detail
- `zap-api-report.json` — machine-readable JSON for CI integration
- `zap-api-report.md` — markdown summary
