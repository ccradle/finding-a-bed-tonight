Feature: HMIS Bridge — Security

  Background:
    * url baseUrl

  Scenario: Outreach worker cannot access HMIS push
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/hmis/push'
    When method POST
    Then status 403

  Scenario: Outreach worker cannot access HMIS vendors
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/hmis/vendors'
    When method GET
    Then status 403

  Scenario: Outreach worker cannot access HMIS status
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/hmis/status'
    When method GET
    Then status 403
