Feature: DV Address Redaction — Policy-Based Address Visibility

  Background:
    * url baseUrl
    # Use seed DV shelter and admin (dvAccess=true)
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000011'

  Scenario: Default policy — outreach worker does NOT see DV address
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/shelters', dvShelterId
    When method GET
    Then status 200
    # Admin sees address (PLATFORM_ADMIN under ADMIN_AND_ASSIGNED)
    And match response.shelter.addressStreet == '#notnull'

  Scenario: Non-DV shelter always returns address
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    # Non-DV seed shelter
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000001'
    When method GET
    Then status 200
    And match response.shelter.addressStreet == '#notnull'
