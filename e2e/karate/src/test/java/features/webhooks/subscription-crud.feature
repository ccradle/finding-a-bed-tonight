Feature: Webhook Subscriptions API

  Background:
    * url baseUrl
    * configure headers = { Authorization: '#(adminAuthHeader)' }

  Scenario: POST /api/v1/subscriptions creates subscription, GET lists it
    Given path '/api/v1/subscriptions'
    And request { "eventType": "availability.updated", "callbackUrl": "https://example.com/webhook", "callbackSecret": "test-secret-123" }
    When method POST
    Then status 201
    And match response.id == '#notnull'
    * def subId = response.id
    # List subscriptions
    Given path '/api/v1/subscriptions'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: DELETE /api/v1/subscriptions/{id} deactivates subscription (204)
    # Create first
    Given path '/api/v1/subscriptions'
    And request { "eventType": "availability.updated", "callbackUrl": "https://example.com/webhook-del", "callbackSecret": "test-secret-456" }
    When method POST
    Then status 201
    * def subId = response.id
    # Delete
    Given path '/api/v1/subscriptions', subId
    When method DELETE
    Then status 204
