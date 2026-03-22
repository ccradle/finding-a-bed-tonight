@observability
Feature: End-to-end OTel trace verification
  Verifies that API requests produce traces visible in Jaeger.
  Requires backend with tracing enabled + Jaeger running (observability profile).
  Assert by processes[key].serviceName, NOT operationName (portfolio Lesson 28).

  Background:
    * url baseUrl
    * configure headers = { Authorization: '#(outreachAuthHeader)', 'Content-Type': 'application/json' }

  Scenario: API request produces trace in Jaeger
    # Trigger an API request that should produce a trace
    Given path '/api/v1/queries/beds'
    And request { populationType: 'individuals' }
    When method POST
    Then status 200

    # Poll Jaeger API until trace appears (up to 30s)
    # Use java.lang.Thread.sleep() in JS functions, not karate.pause() (portfolio Lesson 29)
    * def pollForTrace =
    """
    function() {
      for (var i = 0; i < 15; i++) {
        var result = karate.call('classpath:observability/get-traces.feature');
        var traces = result.traces;
        if (traces && traces.length > 0) {
          for (var t = 0; t < traces.length; t++) {
            var trace = traces[t];
            if (trace.processes) {
              var keys = Object.keys(trace.processes);
              for (var k = 0; k < keys.length; k++) {
                var proc = trace.processes[keys[k]];
                if (proc.serviceName === 'finding-a-bed-tonight') {
                  return true;
                }
              }
            }
          }
        }
        java.lang.Thread.sleep(2000);
      }
      return false;
    }
    """
    * def found = pollForTrace()
    * assert found == true
