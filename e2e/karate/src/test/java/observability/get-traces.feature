@ignore
Feature: Jaeger trace helper
  Helper feature to fetch traces from Jaeger REST API.
  Tagged @ignore to prevent standalone execution by KarateRunner (portfolio Lesson 32).

  Scenario: Fetch traces from Jaeger
    Given url jaegerBaseUrl
    And path '/api/traces'
    And param service = 'finding-a-bed-tonight'
    And param limit = 20
    When method GET
    Then status 200
    And def traces = response.data
