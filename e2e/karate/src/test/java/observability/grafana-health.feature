@observability
Feature: Grafana dashboard verification
  Verifies Grafana is healthy and the FABT operations dashboard is provisioned.
  Requires the observability profile to be running.

  Scenario: Grafana health check
    Given url grafanaBaseUrl
    And path '/api/health'
    When method GET
    Then status 200

  Scenario: FABT operations dashboard is provisioned
    Given url grafanaBaseUrl
    And path '/api/search'
    And param query = 'FABT'
    And header Authorization = 'Basic ' + java.util.Base64.getEncoder().encodeToString('admin:admin'.getBytes())
    When method GET
    Then status 200
    And assert response.length > 0
    And match response[*].title contains 'FABT Operations'
