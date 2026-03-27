Feature: Security response headers
  Verifies that all required security headers are present on responses.
  Headers are set by SecurityHeadersFilter (Spring) and nginx (production).
  Tests run against the backend directly, verifying the Spring layer.

  Background:
    * url baseUrl

  Scenario: Security headers present on authenticated API response
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/shelters'
    When method GET
    Then status 200
    And match responseHeaders['X-Content-Type-Options'][0] == 'nosniff'
    And match responseHeaders['X-Frame-Options'][0] == 'DENY'
    And match responseHeaders['Referrer-Policy'][0] == 'strict-origin-when-cross-origin'
    And match responseHeaders['Permissions-Policy'][0] == 'geolocation=(), microphone=(), camera=()'

  Scenario: Security headers present on unauthenticated response
    Given path '/api/v1/shelters'
    When method GET
    Then status 401
    And match responseHeaders['X-Content-Type-Options'][0] == 'nosniff'
    And match responseHeaders['X-Frame-Options'][0] == 'DENY'

  Scenario: Security headers present on 404 response
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/nonexistent-endpoint'
    When method GET
    Then status 404
    And match responseHeaders['X-Content-Type-Options'][0] == 'nosniff'
    And match responseHeaders['X-Frame-Options'][0] == 'DENY'
