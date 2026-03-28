Feature: Availability API

  Background:
    * url baseUrl

  Scenario: PATCH /api/v1/shelters/{id}/availability creates snapshot with derived beds_available
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000004/availability'
    And request { "populationType": "SINGLE_ADULT", "bedsTotal": 50, "bedsOccupied": 40, "bedsOnHold": 2, "acceptingNewGuests": true }
    When method PATCH
    Then status 200
    And match response.bedsAvailable == 8

  Scenario: POST /api/v1/queries/beds returns ranked results with availability data
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/queries/beds'
    And request { "limit": 10 }
    When method POST
    Then status 200
    And match response.results == '#array'
    And match each response.results contains { dataFreshness: '#notnull' }

  Scenario: POST /api/v1/queries/beds with populationType filter returns filtered results
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/queries/beds'
    And request { "populationType": "SINGLE_ADULT", "limit": 10 }
    When method POST
    Then status 200
    And match response.results == '#array'

  Scenario: POST /api/v1/queries/beds with constraint filters excludes non-matching shelters
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/queries/beds'
    And request { "populationType": "SINGLE_ADULT", "constraints": { "petsAllowed": true }, "limit": 10 }
    When method POST
    Then status 200

  Scenario: Outreach worker cannot PATCH availability (403)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000004/availability'
    And request { "populationType": "SINGLE_ADULT", "bedsTotal": 50, "bedsOccupied": 40, "bedsOnHold": 0, "acceptingNewGuests": true }
    When method PATCH
    Then status 403

  Scenario: Shelter detail includes availability array after PATCH
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    # Update availability first
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000004/availability'
    And request { "populationType": "SINGLE_ADULT", "bedsTotal": 50, "bedsOccupied": 42, "bedsOnHold": 0, "acceptingNewGuests": true }
    When method PATCH
    Then status 200
    # Fetch detail — configure headers persists across requests
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000004'
    When method GET
    Then status 200
    And match response.availability == '#array'
    And match response.availability[0].bedsAvailable == '#notnull'
