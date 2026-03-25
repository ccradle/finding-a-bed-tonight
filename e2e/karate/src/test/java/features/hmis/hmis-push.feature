Feature: HMIS Bridge — Push and Preview

  Background:
    * url baseUrl

  Scenario: Preview returns inventory data
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/hmis/preview'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Status returns vendor and dead letter info
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/hmis/status'
    When method GET
    Then status 200
    And match response.vendors == '#array'
    And match response.deadLetterCount == '#number'

  Scenario: Manual push succeeds for admin
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/hmis/push'
    When method POST
    Then status 200
    And match response.outboxEntriesCreated == '#number'
