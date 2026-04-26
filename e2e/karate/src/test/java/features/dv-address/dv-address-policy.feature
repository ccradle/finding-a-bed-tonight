Feature: DV Address Policy — Change Endpoint Safeguards

  Background:
    * url baseUrl
    * def tenantId = 'a0000000-0000-0000-0000-000000000001'

  Scenario: Policy change without confirmation header rejected
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/tenants', tenantId, 'dv-address-policy'
    And request { "policy": "NONE" }
    When method PUT
    Then status 400
    And match response.error contains 'X-Confirm-Policy-Change'

  Scenario: Policy change with invalid value rejected
    * configure headers = { Authorization: '#(adminAuthHeader)', 'X-Confirm-Policy-Change': 'CONFIRM' }
    Given path '/api/v1/tenants', tenantId, 'dv-address-policy'
    And request { "policy": "INVALID_VALUE" }
    When method PUT
    Then status 400
    And match response.validPolicies contains 'ADMIN_AND_ASSIGNED'

  Scenario: Outreach worker cannot change policy
    * configure headers = { Authorization: '#(outreachAuthHeader)', 'X-Confirm-Policy-Change': 'CONFIRM' }
    Given path '/api/v1/tenants', tenantId, 'dv-address-policy'
    And request { "policy": "NONE" }
    When method PUT
    Then status 403

  # G-4.4 §F21 follow-up: the "valid policy change succeeds" happy-path
  # scenario was removed from this feature on 2026-04-26. After G-4.4 the
  # /dv-address-policy endpoint is @PlatformAdminOnly, requiring a
  # platform-operator JWT (iss=fabt-platform, mfaVerified=true) +
  # X-Platform-Justification header + X-Confirm-Policy-Change header.
  # Karate currently has no platform-operator login helper (TOTP
  # generation in JS + /auth/platform/login + /verify-mfa flow). The
  # backend IT DvAddressRedactionTest covers the happy path with 13
  # scenarios + the helper TestAuthHelper.platformOperatorHeaders().
  # Coverage parity is preserved without the Karate refactor; F21
  # tracks adding Karate platform-operator support for future cross-
  # layer coverage if needed.
