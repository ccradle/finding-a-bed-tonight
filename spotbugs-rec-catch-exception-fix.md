# SpotBugs REC_CATCH_EXCEPTION Fix

**Date:** 2026-03-21
**Tool:** SpotBugs (via portfolio-security-scan Jenkins job, build #33)
**Severity:** Medium (STYLE category)
**File:** `backend/src/main/java/org/fabt/reservation/service/ReservationService.java`
**Method:** `getHoldDurationMinutes` (lambda `lambda$getHoldDurationMinutes$6`)

## Finding

SpotBugs flagged `REC_CATCH_EXCEPTION` — the method caught broad `Exception` in two places when narrower, specific exception types were appropriate.

The `getHoldDurationMinutes` method reads tenant configuration JSON to determine how long a bed hold lasts. It has two try/catch blocks:

1. **Inner catch (lambda):** Wraps `objectMapper.readTree()` which only throws `JsonProcessingException`
2. **Outer catch:** Wraps `tenantService.findById()` which delegates to Spring Data's `findById` — the only unchecked exception path is `DataAccessException`

Both were catching `Exception`, which masks the actual failure modes and could silently swallow unexpected errors (e.g., `NullPointerException` from a bug) by returning the default hold duration instead of surfacing them.

## Change

```diff
- } catch (Exception e) {
+ } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return DEFAULT_HOLD_DURATION_MINUTES;
  }

- } catch (Exception e) {
+ } catch (org.springframework.dao.DataAccessException e) {
      return DEFAULT_HOLD_DURATION_MINUTES;
  }
```

Both catches still return `DEFAULT_HOLD_DURATION_MINUTES` on failure — the behavior is unchanged for expected error paths. The difference is that unexpected exceptions (programming errors, null pointer bugs) will now propagate rather than being silently swallowed.

## Context

This was the only non-`EI_EXPOSE_REP` finding out of 90 medium-severity SpotBugs results in the security scan. The remaining 89 findings are all `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` (mutable object exposure in DTOs and constructors) — standard SpotBugs noise for Spring Boot request/response objects and not a security concern.
