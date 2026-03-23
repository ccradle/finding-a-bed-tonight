# Security Scan Report — Finding A Bed Tonight

**Date:** 2026-03-22
**Scanner:** OWASP Dependency-Check 12.2.0, SpotBugs, PMD, Semgrep, Gitleaks
**Jenkins Build:** portfolio-security-scan #41
**Spring Boot Version:** 3.4.4
**Embedded Tomcat:** 10.1.39 (via Spring Boot BOM)

---

## Executive Summary

The security scan identified **3 critical**, **10 high**, **8 medium**, and **1 low** vulnerability across the project's dependency tree. The vast majority trace to two root causes:

1. **Embedded Tomcat 10.1.39** — 13 CVEs (3 critical, 8 high, 2 medium)
2. **Swagger UI 5.20.1 (DOMPurify)** — 2 medium XSS vulnerabilities

Both are resolved by upgrading Spring Boot and springdoc-openapi. No secrets were detected (Gitleaks clean). No custom code vulnerabilities were found (Semgrep clean for application code; 2 Terraform infrastructure findings are addressed separately below).

---

## Critical & High Vulnerabilities

### Tomcat Embed Core 10.1.39 — 13 CVEs

All vulnerabilities are inherited via `spring-boot-starter-web` → `tomcat-embed-core`. No direct Tomcat configuration exists in the project (embedded Tomcat via Spring Boot).

| CVE | Severity | Description | Relevant? |
|-----|----------|-------------|-----------|
| **CVE-2025-31651** | CRITICAL (9.8) | Escape/meta sequence injection — subset of URL patterns bypass security constraints | **Yes** — affects request routing |
| **CVE-2025-55754** | CRITICAL (9.6) | ANSI escape sequence injection in error pages | **Yes** — affects error handling |
| **CVE-2025-66614** | CRITICAL (9.1) | Improper input validation | **Yes** — affects request processing |
| CVE-2025-49124 | HIGH (8.4) | Untrusted search path in Windows installer | **No** — installer-only, not embedded Tomcat |
| CVE-2025-31650 | HIGH (7.5) | Invalid HTTP priority header causes DoS | **Yes** — affects HTTP/2 handling |
| CVE-2025-48988 | HIGH (7.5) | Resource allocation without limits | **Yes** — DoS vector |
| CVE-2025-48989 | HIGH (7.5) | "Made you reset" attack via improper resource release | **Yes** — HTTP/2 RST_STREAM abuse |
| CVE-2025-49125 | HIGH (7.5) | Auth bypass via PreResources/PostResources | **Unlikely** — requires specific Tomcat resource config not used by Spring Boot |
| CVE-2025-52520 | HIGH (7.5) | Integer overflow in multipart upload | **Possible** — if multipart uploads are enabled |
| CVE-2025-53506 | HIGH (7.5) | HTTP/2 SETTINGS frame resource consumption | **Yes** — affects HTTP/2 |
| CVE-2025-55752 | HIGH (7.5) | Relative path traversal (regression from bug 60013 fix) | **Yes** — affects static resource serving |
| CVE-2026-24734 | HIGH (7.5) | OCSP responder input validation (Tomcat Native) | **No** — requires Tomcat Native library, not used with embedded Tomcat |
| CVE-2025-46701 | HIGH (7.3) | Case sensitivity bypass in CGI servlet | **No** — CGI servlet not used in Spring Boot |

**Action Required:** Upgrade Spring Boot to latest 3.4.x (or 3.5.x if available). This is the single highest-impact fix — resolves 13 CVEs at once.

---

## Medium Vulnerabilities

### 1. Swagger UI 5.20.1 — DOMPurify XSS (2 CVEs)

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2025-15599** | MEDIUM | DOMPurify 3.1.3–3.2.6 XSS via crafted HTML |
| **CVE-2026-0540** | MEDIUM | DOMPurify 3.1.3–3.3.1 XSS (separate vector) |

**Risk Assessment: ELEVATED**

Swagger UI is currently:
- **Publicly accessible** — `SecurityConfig.java` permits all requests to `/swagger-ui/**`, `/api/v1/docs/**`, and `/api/v1/api-docs/**`
- **Not restricted to any Spring profile** — available in all deployment tiers (lite, standard, full)
- **Version:** springdoc-openapi `2.8.6` bundles swagger-ui `5.20.1` with vulnerable DOMPurify

An attacker could craft a malicious API description or exploit the XSS to execute scripts in the context of anyone viewing the Swagger UI.

**Actions Required (choose one or both):**

1. **Upgrade springdoc-openapi** to a version bundling swagger-ui with DOMPurify >= 3.3.2:
   ```xml
   <!-- pom.xml -->
   <springdoc.version>2.9.0</springdoc.version>  <!-- or latest stable -->
   ```

2. **Restrict Swagger UI to dev/local profiles only** (recommended for production regardless):
   ```yaml
   # application-prod.yml (or application-lite.yml etc.)
   springdoc:
     api-docs:
       enabled: false
     swagger-ui:
       enabled: false
   ```
   And update `SecurityConfig.java` to conditionally permit Swagger paths only when springdoc is enabled.

### 2. PostgreSQL JDBC Driver 42.7.5

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2025-49146** | MEDIUM | Config-dependent vulnerability in pgjdbc 42.7.4–42.7.6 |

**Risk Assessment: LOW-MEDIUM**

This vulnerability requires non-default JDBC configuration (e.g., `preferQueryMode=simple`). The project uses standard Spring Boot DataSource configuration with no custom query mode settings.

**Action:** Upgrade to `postgresql >= 42.7.7` by adding a property override in pom.xml:
```xml
<properties>
    <postgresql.version>42.7.7</postgresql.version>
</properties>
```
Or wait for the next Spring Boot BOM bump to include it.

### 3. Log4j API 2.24.3 — Socket Appender TLS

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2025-68161** | MEDIUM | Socket Appender doesn't verify TLS hostname |

**Risk Assessment: NONE**

This only affects the Log4j Socket Appender, which sends logs to a remote socket over TLS. FABT uses SLF4J/Logback (Spring Boot default) for logging to stdout/file. The `log4j-api` dependency is present only as a bridge artifact — the Socket Appender is never instantiated.

**Action:** Suppress. No code change needed.

### 4. Commons Lang3 3.17.0 — Uncontrolled Recursion

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2025-48924** | MEDIUM | Recursive toString/equals on deeply nested structures |

**Risk Assessment: LOW**

Exploitation requires attacker-controlled, deeply nested objects passed to `ToStringBuilder` or `EqualsBuilder` reflective methods. FABT does not use reflective toString/equals on untrusted input.

**Action:** Upgrade when Spring Boot bumps it. No immediate action needed.

### 5. Kotlin Stdlib 1.9.25 — Temp File Creation

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2020-29582** | MEDIUM (5.0) | Insecure temp file/folder creation in Kotlin < 1.4.21 |

**Risk Assessment: NONE**

FABT is a Java project. `kotlin-stdlib` is a transitive dependency from Spring Boot. The vulnerable `createTempDir`/`createTempFile` Kotlin APIs are not called by any Java code in this project. The vulnerability also requires local attacker access to the filesystem.

**Action:** Suppress. This is a transitive dependency not used by application code.

### 6. Tomcat Embed Core — Session Fixation & Resource Leak

| CVE | CVSS | Description |
|-----|------|-------------|
| **CVE-2025-55668** | MEDIUM | Session fixation via rewrite valve |
| **CVE-2025-61795** | MEDIUM | Improper resource release on error |

**Risk Assessment:**
- **CVE-2025-55668: NONE** — requires Tomcat's rewrite valve, which is not configured. FABT uses embedded Tomcat via Spring Boot with no custom valve configuration.
- **CVE-2025-61795: LOW** — resource leak on error conditions. Resolved by the Tomcat upgrade.

**Action:** Resolved by the Spring Boot upgrade (same as critical Tomcat CVEs above).

---

## Low Vulnerabilities

| CVE | Dependency | Description | Action |
|-----|-----------|-------------|--------|
| CVE-2026-24733 | tomcat-embed-core 10.1.39 | HTTP/0.9 not restricted to GET | Resolved by Spring Boot upgrade |

---

## Infrastructure Findings (Semgrep — Terraform)

These are outside the application code but were flagged during the scan:

| Rule | File | Description | Action |
|------|------|-------------|--------|
| `aws-dynamodb-table-unencrypted` | `infra/terraform/bootstrap/main.tf:90` | DynamoDB state lock table uses AWS-managed encryption (default), not CMK. Semgrep wants an explicit `server_side_encryption` block. | **Low priority** — AWS encrypts DynamoDB at rest by default. Add explicit block for compliance documentation if required. |
| `insecure-load-balancer-tls-version` | `infra/terraform/modules/app/main.tf:213` | ALB may use TLS < 1.2. | **Verify** `ssl_policy` is set to `ELBSecurityPolicy-TLS13-1-2-2021-06` or newer. If missing, add it. |

---

## Static Analysis (SpotBugs)

SpotBugs reported ~70 instances of `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` (exposing internal representation). **All are false positives** — the flagged classes are Java records, DTOs, and immutable value objects where returning the internal reference is intentional and safe:

- Request/Response DTOs (`CreateUserRequest`, `ShelterDetailResponse`, etc.)
- Domain value objects (`BedSearchResult`, `ShelterConstraints`, `DomainEvent`)
- Service classes returning query results

**Action:** No changes needed. Consider adding a SpotBugs exclusion filter for `EI_EXPOSE_REP` on record/DTO packages to clean up future reports.

---

## Other Scans — Clean

| Tool | Result |
|------|--------|
| **Gitleaks** | No secrets detected |
| **PMD** | No findings |
| **Semgrep (application code)** | No findings (Java, Python, TypeScript rulesets) |

---

## Recommended Upgrade Plan

### Priority 1 — Spring Boot Upgrade (resolves 16 of 23 CVEs)

Upgrade Spring Boot from `3.4.4` to the latest `3.4.x` patch (or `3.5.x` if stable). This single change resolves:
- All 13 Tomcat CVEs (critical + high + medium)
- Likely bumps commons-lang3, postgresql, and kotlin-stdlib

```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.x</version>  <!-- latest stable -->
</parent>
```

### Priority 2 — springdoc-openapi Upgrade (resolves 2 XSS CVEs)

```xml
<!-- pom.xml -->
<springdoc.version>2.9.0</springdoc.version>  <!-- or latest with DOMPurify >= 3.3.2 -->
```

Additionally, restrict Swagger UI to non-production profiles:
```yaml
# application-prod.yml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

### Priority 3 — PostgreSQL Driver Override (if not resolved by Spring Boot bump)

```xml
<!-- pom.xml properties -->
<postgresql.version>42.7.7</postgresql.version>
```

### Priority 4 — Terraform TLS Policy

Verify ALB ssl_policy in `infra/terraform/modules/app/main.tf:213`:
```hcl
resource "aws_lb_listener" "https" {
  ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  # ...
}
```

### Suppressions (no action needed)

| CVE | Reason |
|-----|--------|
| CVE-2025-68161 (log4j Socket Appender) | Socket Appender not used; SLF4J/Logback is the logging backend |
| CVE-2020-29582 (kotlin-stdlib temp files) | Transitive dep; Kotlin temp file APIs not called from Java code |
| CVE-2025-49124 (Tomcat Windows installer) | Installer-only; not applicable to embedded Tomcat |
| CVE-2026-24734 (Tomcat Native OCSP) | Tomcat Native not used with embedded Tomcat |
| CVE-2025-46701 (Tomcat CGI servlet) | CGI servlet not used in Spring Boot |
| CVE-2025-55668 (Tomcat rewrite valve) | Rewrite valve not configured |

---

*Generated by portfolio-security-scan Jenkins pipeline. Full HTML report available at Jenkins build #41.*
