# Security Scan Report — finding-a-bed-tonight

**Date:** 2026-03-20
**Latest build:** portfolio-security-scan #31
**Scanner:** portfolio-test-automation security pipeline
**Tools:** SpotBugs 4.8.6, PMD 7.7.0, Checkstyle 9.3, Semgrep 1.67.0, Gitleaks, OWASP Dependency-Check

---

## Summary

| Metric | Count |
|--------|-------|
| CRITICAL | 0 |
| HIGH | 0 |
| MEDIUM | 84 |
| LOW | 0 |
| Semgrep (SAST) | 0 (4 suppressed via .semgrepignore) |
| Gitleaks (secrets) | 0 |
| OWASP Dep-Check (CVEs) | 0 |

---

## Fix Applied — Dead Local Store (DLS_DEAD_LOCAL_STORE)

**File:** `backend/src/main/java/org/fabt/availability/service/AvailabilityService.java`
**Method:** `createSnapshot()` (line 83)
**Tool:** SpotBugs (P2, STYLE category)

The variable `previous` was assigned from `repository.findPreviousByShelterId()` but never read. The code was refactored to use `previousForType` (derived from `findLatestByShelterId()`) instead, making the `previous` assignment dead code and a wasted database query.

**What was removed:**

```diff
-        // Get previous snapshot for event payload
-        BedAvailability previous = repository.findPreviousByShelterId(shelterId, populationType);
-        // Actually, we need the current latest BEFORE inserting the new one
-        List<BedAvailability> currentLatest = repository.findLatestByShelterId(shelterId);
+        // Get current latest BEFORE inserting the new one (for event payload delta)
+        List<BedAvailability> currentLatest = repository.findLatestByShelterId(shelterId);
```

**What was NOT removed (kept for future use):**

`BedAvailabilityRepository.findPreviousByShelterId(UUID shelterId, String populationType)` — this method returns the second-most-recent snapshot for a shelter + population type. While it currently has no callers after the dead store fix, it may be needed for future features (e.g., trend analysis, delta reporting). It should be reviewed during the next cleanup pass and removed if still unused.

---

## Remaining Findings (83 MEDIUM)

All 83 remaining findings are SpotBugs Priority 2:

| Type | Count | Category | Assessment |
|------|-------|----------|------------|
| EI_EXPOSE_REP2 | 55 | MALICIOUS_CODE | Not a security issue — Spring DI constructor injection and DTO constructors storing mutable params |
| EI_EXPOSE_REP | 28 | MALICIOUS_CODE | Not a security issue — returning `List`/`Map` fields from DTOs and domain objects |

These are defensive-copy warnings, not security vulnerabilities. The `MALICIOUS_CODE` category name is misleading. In a Spring Boot application with all-internal callers, the risk is negligible.

### Affected modules

| Module | EI_EXPOSE_REP | EI_EXPOSE_REP2 | Total |
|--------|---------------|-----------------|-------|
| auth | 5 | 10 | 15 |
| shelter | 5 | 10 | 15 |
| availability | 4 | 8 | 12 |
| dataimport | 6 | 10 | 16 |
| subscription | 1 | 3 | 4 |
| shared | 3 | 8 | 11 |
| tenant | 0 | 1 | 1 |
| observability | 4 | 5 | 9 |

---

## Suppressed Findings (.semgrepignore)

4 Semgrep findings suppressed via `.semgrepignore` in the repo root:

| File | Rule | Reason |
|------|------|--------|
| `infra/scripts/seed-data.sql:21,34,47` | detected-bcrypt-hash | Dev seed user passwords — bcrypt hashes are safe to commit |
| `infra/docker/nginx.conf:14` | request-host-used | Standard `proxy_set_header Host $host` in reverse proxy config |

---

## Previously Fixed Issues

These were found in earlier scans and fixed in prior commits:

| Issue | File | Fix | Commit |
|-------|------|-----|--------|
| Empty catch in security filter | `ApiKeyAuthenticationFilter.java:59` | Added logging | `cad605c` |
| Unused import `java.util.UUID` | `AuthController.java:3` | Removed | `cad605c` |
| Simplifiable ternary | `UserController.java:48` | Simplified to `\|\|` | `cad605c` |
| Unused import `ShelterCapacity` | `ShelterDetailResponse.java:5` | Removed | `55623dd` |
| Duplicate branches (else if/else) | `TwoOneOneImportAdapter.java:201-206` | Merged to single else | `55623dd` |

---

## Scan History

| Build | CRITICAL | HIGH | MEDIUM | Notes |
|-------|----------|------|--------|-------|
| #20 | 0 | 0 | 107 | Initial (inflated — stale reports + wrong checkstyle config) |
| #23 | 0 | 0 | 20 | Fixed checkstyle property, added reports cleanup |
| #25 | 0 | 0 | 22 | OAuth2 controllers added |
| #26 | 0 | 3 | 69 | Shelter, dataimport, subscription modules |
| #28 | 0 | 0 | 69 | Semgrep false positives suppressed |
| #29 | 0 | 0 | 68 | Unused import + duplicate branches fixed |
| #30 | 0 | 0 | 68 | Terraform added, no new issues |
| #31 | 0 | 0 | 84 | Bed-availability feature added |
