Feature: Batch Jobs — API Management

  Background:
    * url baseUrl

  Scenario: Admin can list batch jobs
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/batch/jobs'
    When method GET
    Then status 200
    And match response == '#array'
    And match each response contains { jobName: '#string', cron: '#string', enabled: '#boolean' }

  Scenario: Admin can view execution history
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/batch/jobs/dailyAggregation/executions'
    When method GET
    Then status 200
    And match response == '#array'

  Scenario: Platform admin can trigger manual run
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    Given path '/api/v1/batch/jobs/dailyAggregation/run'
    And request {}
    When method POST
    Then status 200
    And match response contains { status: 'triggered' }

  Scenario: COC Admin cannot trigger manual run
    * configure headers = { Authorization: '#(cocadminAuthHeader)' }
    Given path '/api/v1/batch/jobs/dailyAggregation/run'
    And request {}
    When method POST
    Then status 403
