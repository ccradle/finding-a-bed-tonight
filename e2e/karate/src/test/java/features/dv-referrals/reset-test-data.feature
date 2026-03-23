@ignore
Feature: Reset test data helper

  Scenario: Call test reset endpoint to clean stale data
    Given url baseUrl
    And path '/api/v1/test/reset'
    And header Authorization = adminAuthHeader
    And header X-Confirm-Reset = 'DESTROY'
    When method DELETE
    Then assert responseStatus == 200 || responseStatus == 404
    # 404 = endpoint doesn't exist (production profile) — that's fine
