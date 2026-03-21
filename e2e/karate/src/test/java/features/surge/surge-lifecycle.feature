Feature: Surge Event Lifecycle API

  Background:
    * url baseUrl

  Scenario: Activate surge event (COC_ADMIN)
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/surge-events'
    And request { "reason": "E2E White Flag test" }
    When method POST
    Then status 201
    And match response.status == 'ACTIVE'
    And match response.reason == 'E2E White Flag test'
    And match response.id == '#notnull'
    * def surgeId = response.id
    # Deactivate it (cleanup)
    Given path '/api/v1/surge-events', surgeId, 'deactivate'
    When method PATCH
    Then status 200
    And match response.status == 'DEACTIVATED'

  Scenario: List surge events
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/surge-events'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Outreach worker cannot activate surge (403)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/surge-events'
    And request { "reason": "Should fail" }
    When method POST
    Then status 403

  Scenario: Outreach worker can list surges (GET is authenticated)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/surge-events'
    When method GET
    Then status 200
