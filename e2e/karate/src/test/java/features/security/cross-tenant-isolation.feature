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

  # OAuth2ProviderController is mapped at /api/v1/tenants/{tenantId}/oauth2-providers
  # — the URL-path {tenantId} exists for back-compat but the controller IGNORES
  # it and sources tenant from TenantContext (Phase 2.1 D11 fix). We pass any
  # UUID for the path-tenantId; the cross-tenant guard fires when the controller
  # looks up the providerId for the JWT's tenant.

  Scenario: OAuth2 provider update with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def pathTenantId = randomUuid()
    * def foreignProviderId = randomUuid()
    Given path '/api/v1/tenants', pathTenantId, 'oauth2-providers', foreignProviderId
    And request { clientId: 'attacker', clientSecret: 'attacker-secret', issuerUri: 'https://accounts.google.com' }
    When method PUT
    Then status 404
    And match response.error == 'not_found'
    # Paranoid forward-looking check: response must not echo attacker input
    # in any field. ErrorResponse envelope has no clientId field today.
    And match response !contains { clientId: 'attacker' }

  Scenario: OAuth2 provider delete with foreign UUID returns 404
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def pathTenantId = randomUuid()
    * def foreignProviderId = randomUuid()
    Given path '/api/v1/tenants', pathTenantId, 'oauth2-providers', foreignProviderId
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
    # Paranoid forward-looking check: ErrorResponse envelope has no
    # plaintextKey field today; this catches a hypothetical future bug
    # where a 404 path accidentally echoes the success-path response.
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
    # Paranoid forward-looking check: 404 envelope has no code/plaintextCode
    # fields today. Catches a hypothetical regression that leaks the secret.
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
    # Paranoid forward-looking check: 404 envelope has no backupCodes
    # field today. Catches a hypothetical regression that returns the
    # newly-generated codes (the success-path response) on a 404 — which
    # would be account takeover pivot.
    And match response !contains { backupCodes: '#notnull' }

  # ================================================================
  # Same-tenant positive controls (warroom Phase 5 review action item 3).
  # The 8 scenarios above prove cross-tenant access returns 404. These 5
  # scenarios prove same-tenant access STILL WORKS — catches a refactor
  # regression that broke "admin can manage their OWN resources" without
  # the smoke specs going silent. Read-only checks (no state mutation).
  # ================================================================

  Scenario: Same-tenant admin can list their own OAuth2 providers
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def selfTenantId = randomUuid()
    Given path '/api/v1/tenants', selfTenantId, 'oauth2-providers'
    When method GET
    Then status 200
    # Response is an array (possibly empty) — proves the endpoint serves
    # the caller's tenant successfully, not a 404/403/500.
    And match response == '#array'

  Scenario: Same-tenant admin can list their own API keys
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/api-keys'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Same-tenant admin can list their own subscriptions
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/subscriptions'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Same-tenant cocadmin can list their own users
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/users'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Same-tenant outreach worker can list their own shelters
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
    And match response == '#array'

  @ignore
  Scenario: Cross-tenant 404s counter increments after cross-tenant probes
    # Per Phase 4.4, fabt.security.cross_tenant_404s tracks every NoSuchElementException
    # 404. After the 8 scenarios above, the metric should have moved.
    #
    # @ignore by default: the actuator endpoint at managementBaseUrl
    # (port 9091) is INTERNAL-ONLY in production — bound to localhost on
    # the Oracle VM behind nginx, NOT exposed publicly. Running this
    # scenario against findabed.org would fail with connection refused.
    # Run only in dev (managementBaseUrl=http://localhost:9091) or from
    # within the VM. To enable: remove the @ignore tag locally.
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def metricsUrl = managementBaseUrl + '/actuator/prometheus'
    Given url metricsUrl
    When method GET
    Then status 200
    And match response contains 'fabt_security_cross_tenant_404s_total'
