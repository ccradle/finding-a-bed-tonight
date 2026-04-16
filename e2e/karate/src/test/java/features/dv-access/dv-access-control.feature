Feature: DV Shelter Access Control — Blocking Canary Gate

  This is a safety-critical test. If any scenario fails, the entire E2E pipeline
  must halt. A DV shelter appearing in a public query is a data protection failure.

  Background:
    * url baseUrl
    * def dvShelterId = 'd0000000-0000-0000-0000-000000000011'

  Scenario: DV shelter absent from public bed search
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/queries/beds'
    And request { "limit": 50 }
    When method POST
    Then status 200
    # Verify no result contains the DV shelter UUID
    * def dvResults = karate.filter(response.results, function(x){ return x.shelterId == dvShelterId })
    * match dvResults == '#[0]'
    # Verify no result contains the DV shelter name
    * def dvNameResults = karate.filter(response.results, function(x){ return x.shelterName == 'Safe Haven DV Shelter' })
    * match dvNameResults == '#[0]'

  Scenario: DV shelter absent from shelter list endpoint
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
    * def dvResults = karate.filter(response, function(x){ return x.shelter.id == dvShelterId })
    * match dvResults == '#[0]'

  Scenario: DV shelter direct access returns 404 not 403
    # 404 is correct — 403 would leak existence
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters', dvShelterId
    When method GET
    Then status 404

  Scenario: DV shelter HSDS export returns 404
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/shelters', dvShelterId
    And param format = 'hsds'
    When method GET
    Then status 404

  Scenario: COC_ADMIN without dvAccess cannot see DV shelter
    # The seed cocadmin user now has dvAccess=true (coc-admin-escalation, v0.35.0)
    # because the DV Escalation queue requires it. This test creates a dedicated
    # COC_ADMIN user with dvAccess=false to prove that the ROLE alone is not
    # sufficient — dvAccess is the gate. Per feedback_isolated_test_data: tests
    # must create own data, not depend on seed assumptions.
    #
    # Use a per-run unique email so re-running this feature against the same
    # DB doesn't hit the (tenant_id, email) UNIQUE constraint on app_user.
    * def uniqueEmail = 'dv-canary-noaccess-' + java.util.UUID.randomUUID() + '@dev.fabt.org'
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/users'
    And request { email: '#(uniqueEmail)', displayName: 'DV Canary No Access', password: 'TestPassword123!', roles: ['COC_ADMIN'], dvAccess: false }
    When method POST
    Then status 201
    * def testUserId = response.id

    # Login as the new user
    Given url loginUrl
    And request { email: '#(uniqueEmail)', password: 'TestPassword123!', tenantSlug: '#(tenantSlug)' }
    When method POST
    Then status 200
    * def noDvToken = response.accessToken

    # Assert: DV shelter is invisible to this COC_ADMIN without dvAccess
    * configure headers = { Authorization: '#("Bearer " + noDvToken)' }
    * url baseUrl
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
    * def dvResults = karate.filter(response, function(x){ return x.shelter.id == dvShelterId })
    * match dvResults == '#[0]'
