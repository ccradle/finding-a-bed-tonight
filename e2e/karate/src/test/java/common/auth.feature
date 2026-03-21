@ignore
Feature: Auth Helper — reusable login that returns JWT token

  Scenario: Login and return access token
    Given url loginUrl
    And request { tenantSlug: '#(tenantSlug)', email: '#(email)', password: '#(password)' }
    When method POST
    Then status 200
    * def accessToken = response.accessToken
    * def refreshToken = response.refreshToken
