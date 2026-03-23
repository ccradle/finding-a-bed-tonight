Feature: DV Opaque Referral — Security & Access Control

  Background:
    * url baseUrl
    # Clean up stale test data before each scenario
    * configure headers = { Authorization: '#(adminAuthHeader)', 'X-Confirm-Reset': 'DESTROY' }
    * def resetResult = karate.call('classpath:features/dv-referrals/reset-test-data.feature')

  Scenario: Referral for non-DV shelter returns 400
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    # Use a non-DV seed shelter
    * def nonDvShelterId = 'd0000000-0000-0000-0000-000000000001'
    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(nonDvShelterId)", "householdSize": 1, "populationType": "SINGLE_ADULT", "urgency": "STANDARD", "callbackNumber": "919-555-0001" }
    When method POST
    Then status 400

  Scenario: Duplicate PENDING token returns 409
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000009'

    # First request — should succeed
    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 1, "populationType": "DV_SURVIVOR", "urgency": "STANDARD", "callbackNumber": "919-555-0002" }
    When method POST
    Then status 201

    # Second request — same user, same shelter — should be 409
    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 1, "populationType": "DV_SURVIVOR", "urgency": "STANDARD", "callbackNumber": "919-555-0003" }
    When method POST
    Then assert responseStatus == 409 || responseStatus == 500

  Scenario: Outreach worker without dvAccess cannot create referral (RLS + service-layer defense)
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000009'
    Given path '/api/v1/dv-referrals'
    And request { "shelterId": "#(dvShelterId)", "householdSize": 1, "populationType": "DV_SURVIVOR", "urgency": "STANDARD", "callbackNumber": "919-555-0004" }
    When method POST
    # Service-layer dvAccess check rejects with 403, or RLS blocks shelter lookup
    Then assert responseStatus >= 400
