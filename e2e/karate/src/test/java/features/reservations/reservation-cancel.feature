Feature: Reservation Cancel Releases Bed

  Background:
    * url baseUrl
    * def shelterId = 'd0000000-0000-0000-0000-000000000002'

  Scenario: Cancel reservation releases the held bed
    # Set availability
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/shelters', shelterId, 'availability'
    And request { "populationType": "FAMILY_WITH_CHILDREN", "bedsTotal": 30, "bedsOccupied": 20, "bedsOnHold": 0, "acceptingNewGuests": true }
    When method PATCH
    Then status 200

    # Create reservation
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/reservations'
    And request { "shelterId": "#(shelterId)", "populationType": "FAMILY_WITH_CHILDREN" }
    When method POST
    Then status 201
    * def reservationId = response.id

    # Cancel it
    Given path '/api/v1/reservations', reservationId, 'cancel'
    When method PATCH
    Then status 200
    And match response.status == 'CANCELLED'
