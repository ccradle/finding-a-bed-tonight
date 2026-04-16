Feature: Cross-Tenant Isolation — Live Smoke Test (Issue #117)

  Verifies that the 5 admin surfaces fixed in cross-tenant-isolation-audit
  Phase 2 still return 404 (not 200, not 403, not stack trace) when called
  with a UUID the caller's tenant does not own. Per design D3:
  cross-tenant access returns 404 indistinguishable from "not found" to
  prevent existence leak.

  This spec uses random non-existent UUIDs rather than provisioning a
  secondary tenant per smoke run — the code path exercised
  (findByIdAndTenantId returning Optional.empty → 404) is identical for
  "unknown UUID" and "Tenant B's UUID". The integration test suite
  (CrossTenantIsolationTest, OAuth2ProviderTest, ApiKeyAuthTest, etc.)
  proves the second-tenant case at build time.

  Per spec 5.3.3: this entire feature must add ≤ 30 seconds to post-deploy
  smoke runtime.

  Background:
    * url baseUrl
    * def randomUuid = function() { return java.util.UUID.randomUUID().toString() }

  Scenario: OAuth2 provider update with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def foreignProviderId = randomUuid()
    Given path '/api/v1/oauth2-providers', foreignProviderId
    And request { clientId: 'attacker', clientSecret: 'attacker-secret', issuerUri: 'https://accounts.google.com' }
    When method PUT
    Then status 404
    And match response.error == 'not_found'
    # Defense-in-depth: response must not echo the attacker's input
    And match response !contains { clientId: 'attacker' }

  Scenario: OAuth2 provider delete with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def foreignProviderId = randomUuid()
    Given path '/api/v1/oauth2-providers', foreignProviderId
    When method DELETE
    Then status 404
    And match response.error == 'not_found'

  Scenario: API key rotate with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def foreignKeyId = randomUuid()
    Given path '/api/v1/api-keys', foreignKeyId, 'rotate'
    When method POST
    Then status 404
    And match response.error == 'not_found'
    # New key must NOT be returned in the response body
    And match response !contains { plaintextKey: '#notnull' }

  Scenario: API key deactivate with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def foreignKeyId = randomUuid()
    Given path '/api/v1/api-keys', foreignKeyId
    When method DELETE
    Then status 404
    And match response.error == 'not_found'

  Scenario: Subscription delete with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def foreignSubscriptionId = randomUuid()
    Given path '/api/v1/subscriptions', foreignSubscriptionId
    When method DELETE
    Then status 404
    And match response.error == 'not_found'

  Scenario: Generate access code for foreign user returns 404
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    * def foreignUserId = randomUuid()
    Given path '/api/v1/users', foreignUserId, 'generate-access-code'
    When method POST
    Then status 404
    And match response.error == 'not_found'
    # The code itself must NOT be returned (would be account takeover)
    And match response !contains { code: '#notnull' }
    And match response !contains { plaintextCode: '#notnull' }

  Scenario: TOTP admin disable with foreign user returns 404
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    * def foreignUserId = randomUuid()
    Given path '/api/v1/auth/totp', foreignUserId
    When method DELETE
    Then status 404
    And match response.error == 'not_found'

  Scenario: TOTP admin regenerate codes for foreign user returns 404
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    * def foreignUserId = randomUuid()
    Given path '/api/v1/auth/totp', foreignUserId, 'regenerate-recovery-codes'
    When method POST
    Then status 404
    And match response.error == 'not_found'
    # Backup codes must NOT be returned (would be account takeover pivot)
    And match response !contains { backupCodes: '#notnull' }

  Scenario: Cross-tenant 404s counter increments after cross-tenant probes
    # Per Phase 4.4, fabt.security.cross_tenant_404s tracks every NoSuchElementException
    # 404. After the 8 scenarios above, the metric should have moved.
    # This scenario is observation-only — verifies the counter exists.
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def metricsUrl = managementBaseUrl + '/actuator/prometheus'
    Given url metricsUrl
    When method GET
    Then status 200
    # The counter is registered lazily on first 404. After the prior scenarios
    # have run within the same smoke pass, it should be present.
    And match response contains 'fabt_security_cross_tenant_404s_total'
