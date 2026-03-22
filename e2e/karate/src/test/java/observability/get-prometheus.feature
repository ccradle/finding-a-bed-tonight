@ignore
Feature: Prometheus metrics helper
  Helper feature to fetch and parse Prometheus metrics from /actuator/prometheus.
  Tagged @ignore to prevent standalone execution by KarateRunner (portfolio Lesson 32).

  Scenario: Fetch Prometheus metrics
    # Use management port (9091) — no auth required (ManagementSecurityConfig permitAll)
    Given url managementBaseUrl
    And path '/actuator/prometheus'
    When method GET
    Then status 200
    And def metricsText = response

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

    * def metricExists =
    """
    function(text, metricName) {
      return text.indexOf(metricName) >= 0;
    }
    """
