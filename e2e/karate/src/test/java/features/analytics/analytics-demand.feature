Feature: Analytics — Demand Signals, DV Summary

  Background:
    * url baseUrl

  Scenario: Admin can query demand signals
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/demand'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    When method GET
    Then status 200
    And match response contains { totalSearches: '#number', zeroResultSearches: '#number' }
    And match response.reservations contains { total: '#number' }

  Scenario: Admin with DV access can query DV summary
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/dv-summary'
    When method GET
    Then status 200

  Scenario: HMIS health endpoint returns push statistics
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/hmis-health'
    When method GET
    Then status 200
    And match response contains { deadLetterCount: '#number' }
