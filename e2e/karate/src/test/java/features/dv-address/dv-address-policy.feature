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

  Scenario: Valid policy change succeeds
    * configure headers = { Authorization: '#(adminAuthHeader)', 'X-Confirm-Policy-Change': 'CONFIRM' }
    Given path '/api/v1/tenants', tenantId, 'dv-address-policy'
    And request { "policy": "ADMIN_AND_ASSIGNED" }
    When method PUT
    Then status 200
    And match response.policy == 'ADMIN_AND_ASSIGNED'
