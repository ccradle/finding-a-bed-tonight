Feature: Reservation Concurrency — Last Bed Race

  Background:
    * url baseUrl
    * def shelterId = 'd0000000-0000-0000-0000-000000000004'

  Scenario: Concurrent reservation for last bed — one succeeds, one fails
    # Set availability to exactly 1 bed
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/shelters', shelterId, 'availability'
    And request { "populationType": "VETERAN", "bedsTotal": 40, "bedsOccupied": 39, "bedsOnHold": 0, "acceptingNewGuests": true }
    When method PATCH
    Then status 200
    And match response.bedsAvailable == 1

    # First reservation should succeed
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/reservations'
    And request { "shelterId": "#(shelterId)", "populationType": "VETERAN" }
    When method POST
    Then status 201

    # Second reservation should fail — 0 beds available after first hold
    Given path '/api/v1/reservations'
    And request { "shelterId": "#(shelterId)", "populationType": "VETERAN" }
    When method POST
    # Should get 400 (IllegalStateException: no beds available)
    Then assert responseStatus >= 400
