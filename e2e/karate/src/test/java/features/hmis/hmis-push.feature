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
    # G-4.4 §F16: COC_ADMIN-tenant-scoped; X-Confirm-HMIS-Push: CONFIRM required.
    * configure headers = { Authorization: '#(adminAuthHeader)', 'X-Confirm-HMIS-Push': 'CONFIRM' }
    Given path '/api/v1/hmis/push'
    When method POST
    Then status 200
    And match response.outboxEntriesCreated == '#number'

  Scenario: Manual push without confirm header → 400 missing_confirmation
    # G-4.4 §F16: confirm-header gate prevents accidental triggers.
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/hmis/push'
    When method POST
    Then status 400
    And match response.error == 'missing_confirmation'
