Feature: DV Opaque Referral — Token Lifecycle

  Background:
    * url baseUrl
    # Clean up stale test data before each scenario
    * configure headers = { Authorization: '#(adminAuthHeader)', 'X-Confirm-Reset': 'DESTROY' }
    * def resetResult = karate.call('classpath:features/dv-referrals/reset-test-data.feature')

  Scenario: Create referral, accept, verify warm handoff response shape
    # Create as admin (has dvAccess=true)
    * configure headers = { Authorization: '#(adminAuthHeader)' }

    # Use the seed DV shelter
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000011'

    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 2, "populationType": "DV_SURVIVOR", "urgency": "URGENT", "specialNeeds": "Wheelchair", "callbackNumber": "919-555-0042" }
    When method POST
    Then status 201
    And match response.id == '#notnull'
    And match response.status == 'PENDING'
    And match response.householdSize == 2
    And match response.urgency == 'URGENT'
    And match response.callbackNumber == '919-555-0042'
    * def tokenId = response.id

    # Accept as admin (dvAccess=true required to see DV referral tokens via RLS)
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/dv-referrals', tokenId, 'accept'
    When method PATCH
    Then status 200
    And match response.status == 'ACCEPTED'
    # Warm handoff: shelter phone included
    And match response.shelterPhone == '#notnull'

    # Verify worker's "mine" list includes phone but NOT address
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/dv-referrals/mine'
    When method GET
    Then status 200
    And match response[0].shelterPhone == '#notnull'
    And match response[0] !contains { addressStreet: '#notnull' }
    And match response[0] !contains { latitude: '#notnull' }

  Scenario: Accept already-accepted token returns 409 with correct error shape
    # Tests the API contract for non-pending token actions.
    # The "expired" path uses the same IllegalStateException → 409 contract.
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000011'

    # Create and accept a referral
    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 1, "populationType": "DV_SURVIVOR", "urgency": "STANDARD", "callbackNumber": "919-555-0097" }
    When method POST
    Then status 201
    * def tokenId = response.id

    Given path '/api/v1/dv-referrals', tokenId, 'accept'
    When method PATCH
    Then status 200

    # Try to accept again — should be 409 with error contract
    Given path '/api/v1/dv-referrals', tokenId, 'accept'
    When method PATCH
    Then status 409
    And match response == { error: '#string', message: '#string', status: 409 }
    And match response.message contains 'not pending'

  Scenario: Reject already-accepted token returns 409 with correct error shape
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000011'

    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 1, "populationType": "DV_SURVIVOR", "urgency": "STANDARD", "callbackNumber": "919-555-0096" }
    When method POST
    Then status 201
    * def tokenId = response.id

    # Accept first
    Given path '/api/v1/dv-referrals', tokenId, 'accept'
    When method PATCH
    Then status 200

    # Try to reject — should be 409
    Given path '/api/v1/dv-referrals', tokenId, 'reject'
    And request { "reason": "Testing reject on accepted" }
    When method PATCH
    Then status 409
    And match response == { error: '#string', message: '#string', status: 409 }
    And match response.message contains 'not pending'
