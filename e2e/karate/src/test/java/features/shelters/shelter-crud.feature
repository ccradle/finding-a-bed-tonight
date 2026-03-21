Feature: Shelter CRUD API

  Background:
    * url baseUrl
    * configure headers = { Authorization: '#(adminAuthHeader)' }

  Scenario: POST /api/v1/shelters creates shelter, GET returns it with constraints and capacities
    * def shelterName = 'E2E Test Shelter ' + java.util.UUID.randomUUID().toString().substring(0, 8)
    Given path '/api/v1/shelters'
    And request
      """
      {
        "name": "#(shelterName)",
        "addressStreet": "100 E2E Test St",
        "addressCity": "Raleigh",
        "addressState": "NC",
        "addressZip": "27601",
        "phone": "919-555-9999",
        "latitude": 35.78,
        "longitude": -78.64,
        "dvShelter": false,
        "constraints": {
          "sobrietyRequired": false,
          "idRequired": false,
          "referralRequired": false,
          "petsAllowed": true,
          "wheelchairAccessible": true,
          "populationTypesServed": ["SINGLE_ADULT"]
        },
        "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 10}]
      }
      """
    When method POST
    Then status 201
    And match response.id == '#notnull'
    And match response.name == shelterName
    * def shelterId = response.id
    # GET shelter detail — configure headers persists across requests
    Given path '/api/v1/shelters', shelterId
    When method GET
    Then status 200
    And match response.shelter.name == shelterName
    And match response.constraints != null
    And match response.capacities != null

  Scenario: PUT /api/v1/shelters/{id} updates shelter fields
    * def shelterName = 'E2E Update Test ' + java.util.UUID.randomUUID().toString().substring(0, 8)
    Given path '/api/v1/shelters'
    And request { "name": "#(shelterName)", "addressStreet": "1 St", "addressCity": "Raleigh", "addressState": "NC", "addressZip": "27601", "dvShelter": false }
    When method POST
    Then status 201
    * def shelterId = response.id
    Given path '/api/v1/shelters', shelterId
    And request { "name": "Updated E2E Shelter" }
    When method PUT
    Then status 200
    And match response.name == 'Updated E2E Shelter'

  Scenario: GET /api/v1/shelters returns list with availability summary
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
    And match response == '#array'
    And match each response contains { shelter: '#notnull' }

  Scenario: GET /api/v1/shelters?petsAllowed=true filters correctly
    Given path '/api/v1/shelters'
    And param petsAllowed = true
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: GET /api/v1/shelters/{id}?format=hsds returns HSDS 3.0 structure
    Given path '/api/v1/shelters/d0000000-0000-0000-0000-000000000001'
    And param format = 'hsds'
    When method GET
    Then status 200
    And match response.organization == '#notnull'
    And match response.service == '#notnull'
    And match response.location == '#notnull'

  Scenario: Outreach worker cannot POST /api/v1/shelters (403)
    # Override configure headers with outreach token for this scenario
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters'
    And request { "name": "Should Fail", "addressStreet": "1 St", "addressCity": "X", "addressState": "NC", "addressZip": "27601", "dvShelter": false }
    When method POST
    Then status 403
