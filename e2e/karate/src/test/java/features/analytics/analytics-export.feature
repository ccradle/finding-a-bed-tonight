Feature: Analytics — HIC and PIT CSV Exports

  Background:
    * url baseUrl

  Scenario: Admin can download HIC CSV
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/hic'
    And param date = '2026-01-29'
    When method GET
    Then status 200
    And match header Content-Type contains 'text/csv'
    And match response contains 'ProjectID,ProjectName,ProjectType'

  Scenario: Admin can download PIT CSV
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/pit'
    And param date = '2026-01-29'
    When method GET
    Then status 200
    And match header Content-Type contains 'text/csv'
    And match response contains 'CoCCode,ProjectType,HouseholdType,TotalPersons'

  Scenario: HIC export without date defaults to today
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/hic'
    When method GET
    Then status 200
