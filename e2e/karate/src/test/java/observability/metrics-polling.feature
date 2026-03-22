@observability
Feature: Metrics polling verification
  Verifies that custom Micrometer counters increment after domain operations.
  Requires the backend to be running. Tagged @observability for selective skip:
  mvn test -Dkarate.options="--tags ~@observability"

  Background:
    * url baseUrl
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def parseCounter =
    """
    function(text, metricName) {
      var lines = text.split('\n');
      var total = 0;
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf('#') === 0) continue;
        if (line.indexOf(metricName) === 0) {
          var parts = line.split(' ');
          if (parts.length >= 2) {
            total += parseFloat(parts[parts.length - 1]);
          }
        }
      }
      return total;
    }
    """

  Scenario: Bed search counter increments after search
    # Record baseline
    * def baseline = call read('classpath:observability/get-prometheus.feature')
    * def baselineCount = parseCounter(baseline.metricsText, 'fabt_bed_search_count_total')

    # Trigger a bed search
    * configure headers = { Authorization: '#(outreachAuthHeader)', 'Content-Type': 'application/json' }
    Given path '/api/v1/queries/beds'
    And request { populationType: 'individuals' }
    When method POST
    Then status 200

    # Poll Prometheus until counter increments (up to 30s)
    * configure headers = { Authorization: '#(adminAuthHeader)' }
    * def pollResult = call read('classpath:observability/get-prometheus.feature')
    * def newCount = parseCounter(pollResult.metricsText, 'fabt_bed_search_count_total')
    * assert newCount > baselineCount
