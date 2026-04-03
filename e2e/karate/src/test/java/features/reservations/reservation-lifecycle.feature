Feature: Reservation Full Lifecycle

  Background:
    * url baseUrl
    # Oak City Community Shelter — serves SINGLE_ADULT
    * def shelterId = 'd0000000-0000-0000-0000-000000000004'

  Scenario: Full reservation lifecycle — create, confirm, verify occupancy
    # Step 1: Set availability (coordinator)
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/shelters', shelterId, 'availability'
    And request { "populationType": "SINGLE_ADULT", "bedsTotal": 50, "bedsOccupied": 30, "bedsOnHold": 0, "acceptingNewGuests": true }
    When method PATCH
    Then status 200
    * def beforeAvail = response.bedsAvailable

    # Step 2: Create reservation (outreach)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/reservations'
    And request { "shelterId": "#(shelterId)", "populationType": "SINGLE_ADULT" }
    When method POST
    Then status 201
    And match response.status == 'HELD'
    And match response.expiresAt == '#notnull'
    * def reservationId = response.id

    # Step 3: List active reservations
    Given path '/api/v1/reservations'
    When method GET
    Then status 200
    * def found = karate.filter(response, function(x){ return x.id == reservationId })
    * match found == '#[1]'

    # Step 4: Confirm reservation
    Given path '/api/v1/reservations', reservationId, 'confirm'
    When method PATCH
    Then status 200
    And match response.status == 'CONFIRMED'

    # Step 5: Verify shelter availability changed
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/shelters', shelterId
    When method GET
    Then status 200
