Feature: Authentication API

  Background:
    * url baseUrl

  Scenario: POST /api/v1/auth/login with valid credentials returns 200 + tokens
    Given path '/api/v1/auth/login'
    And request { tenantSlug: '#(tenantSlug)', email: '#(adminEmail)', password: '#(adminPassword)' }
    When method POST
    Then status 200
    And match response.accessToken == '#notnull'
    And match response.refreshToken == '#notnull'

  Scenario: POST /api/v1/auth/login with invalid credentials returns 401
    Given path '/api/v1/auth/login'
    And request { tenantSlug: '#(tenantSlug)', email: '#(adminEmail)', password: 'wrongpassword' }
    When method POST
    Then status 401

  Scenario: POST /api/v1/auth/refresh with valid refresh token returns new access token
    * def loginResult = call read('classpath:common/auth.feature') { loginUrl: '#(loginUrl)', email: '#(adminEmail)', password: '#(adminPassword)', tenantSlug: '#(tenantSlug)' }
    Given path '/api/v1/auth/refresh'
    And request { refreshToken: '#(loginResult.refreshToken)' }
    When method POST
    Then status 200
    And match response.accessToken == '#notnull'

  Scenario: Protected endpoint without auth header returns 401
    Given path '/api/v1/shelters'
    When method GET
    Then status 401

  Scenario: API key authentication works for shelter endpoints
    # Create an API key using admin JWT
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/api-keys'
    And request { label: 'E2E Test Key' }
    When method POST
    Then status 201
    * def apiKey = response.plaintextKey
    # Switch to API key auth
    * configure headers = null
    * header X-API-Key = apiKey
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
