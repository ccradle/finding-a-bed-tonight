Feature: Analytics — Role-Based Access Control

  Background:
    * url baseUrl

  Scenario: Outreach worker cannot access utilization
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/analytics/utilization'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    And param granularity = 'daily'
    When method GET
    Then status 403

  Scenario: Outreach worker cannot access demand
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/analytics/demand'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    When method GET
    Then status 403

  Scenario: Outreach worker cannot access HIC export
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/analytics/hic'
    When method GET
    Then status 403

  Scenario: Outreach worker cannot access batch jobs
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/batch/jobs'
    When method GET
    Then status 403

  Scenario: COC Admin can access analytics
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/analytics/utilization'
    And param from = '2026-01-01'
    And param to = '2026-12-31'
    And param granularity = 'daily'
    When method GET
    Then status 200

  Scenario: COC Admin can view batch jobs
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/batch/jobs'
    When method GET
    Then status 200
