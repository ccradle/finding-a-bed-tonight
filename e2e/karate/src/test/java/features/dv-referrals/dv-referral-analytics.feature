Feature: DV Referral Analytics — Aggregate Counts

  Background:
    * url baseUrl

  Scenario: Analytics endpoint returns aggregate counts, no PII
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/dv-referrals/analytics'
    When method GET
    Then status 200
    And match response.requested == '#number'
    And match response.accepted == '#number'
    And match response.rejected == '#number'
    And match response.expired == '#number'
    # Must NOT contain any PII fields
    And match response !contains { callbackNumber: '#notnull' }
    And match response !contains { specialNeeds: '#notnull' }
    And match response !contains { householdSize: '#notnull' }
    And match response !contains { shelterId: '#notnull' }

  Scenario: Analytics endpoint rejects outreach worker (403)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/dv-referrals/analytics'
    When method GET
    Then status 403
