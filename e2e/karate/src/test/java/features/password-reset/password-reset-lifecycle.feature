Feature: Password Reset — Lifecycle (Email-Based)

  Background:
    * url baseUrl
    * def tenantSlug = 'dev-coc'

  Scenario: Forgot password returns 200 regardless of email existence
    Given path '/api/v1/auth/forgot-password'
    And request { email: 'outreach@dev.fabt.org', tenantSlug: '#(tenantSlug)' }
    When method POST
    Then status 200
    And match response.message == 'If the email exists, a reset link has been sent.'

  Scenario: Forgot password with non-existent email returns same 200
    Given path '/api/v1/auth/forgot-password'
    And request { email: 'nobody-at-all@dev.fabt.org', tenantSlug: '#(tenantSlug)' }
    When method POST
    Then status 200
    And match response.message == 'If the email exists, a reset link has been sent.'

  Scenario: Forgot password with invalid tenant slug returns same 200
    Given path '/api/v1/auth/forgot-password'
    And request { email: 'outreach@dev.fabt.org', tenantSlug: 'nonexistent-tenant' }
    When method POST
    Then status 200
    And match response.message == 'If the email exists, a reset link has been sent.'

  Scenario: Reset password with invalid token returns 400
    Given path '/api/v1/auth/reset-password'
    And request { token: 'definitely-not-a-real-token', newPassword: 'NewSecurePass12!' }
    When method POST
    Then status 400
    And match response.error == 'invalid_token'

  Scenario: Reset password with short password returns 400
    Given path '/api/v1/auth/reset-password'
    And request { token: 'any-token', newPassword: 'short' }
    When method POST
    Then status 400

  Scenario: Capabilities endpoint returns emailResetAvailable flag
    Given path '/api/v1/auth/capabilities'
    When method GET
    Then status 200
    And match response.emailResetAvailable == '#boolean'
    And match response.totpAvailable == '#boolean'
    And match response.accessCodeAvailable == true
