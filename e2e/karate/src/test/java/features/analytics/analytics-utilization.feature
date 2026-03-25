Feature: Analytics — Utilization, Capacity, Geographic

  Background:
    * url baseUrl

  Scenario: Admin can query utilization endpoint
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/utilization'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    And param granularity = 'daily'
    When method GET
    Then status 200
    And match response contains { avgUtilization: '#present' }

  Scenario: Admin can query capacity endpoint
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/capacity'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    When method GET
    Then status 200
    And match response contains { dailyCapacity: '#present' }

  Scenario: Admin can query geographic endpoint
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/geographic'
    When method GET
    Then status 200
    And match response == '#array'
