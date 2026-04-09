Feature: Password Reset — Security

  Background:
    * url baseUrl
    * def tenantSlug = 'dev-coc'

  Scenario: DV user forgot-password returns same 200 as non-DV (no enumeration)
    # dv-outreach@dev.fabt.org has dvAccess=true — email reset is blocked
    # per NNEDV: compromised email reveals DV platform membership
    Given path '/api/v1/auth/forgot-password'
    And request { email: 'dv-outreach@dev.fabt.org', tenantSlug: '#(tenantSlug)' }
    When method POST
    Then status 200
    And match response.message == 'If the email exists, a reset link has been sent.'

  Scenario: Reset password with empty token returns 400
    Given path '/api/v1/auth/reset-password'
    And request { token: '', newPassword: 'NewSecurePass12!' }
    When method POST
    Then status 400

  Scenario: Reset password with missing fields returns 400
    Given path '/api/v1/auth/reset-password'
    And request {}
    When method POST
    Then status 400

  Scenario: Forgot password with missing email still returns 200 (no crash)
    Given path '/api/v1/auth/forgot-password'
    And request { tenantSlug: '#(tenantSlug)' }
    When method POST
    Then status 200

  Scenario: Forgot password with missing tenantSlug still returns 200 (no crash)
    Given path '/api/v1/auth/forgot-password'
    And request { email: 'outreach@dev.fabt.org' }
    When method POST
    Then status 200
