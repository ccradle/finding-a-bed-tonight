package org.fabt.observability;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NoaaClient {

    private static final Logger log = LoggerFactory.getLogger(NoaaClient.class);

    private final RestClient restClient;
    private final String defaultStationId;

    public NoaaClient(RestClient.Builder restClientBuilder,
                      @Value("${fabt.monitoring.noaa.station-id:KRDU}") String defaultStationId) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.weather.gov")
                .defaultHeader("User-Agent", "(finding-a-bed-tonight, contact@fabt.org)")
                .build();
        this.defaultStationId = defaultStationId;
    }

    public Double getCurrentTemperatureFahrenheit() {
        return getCurrentTemperatureFahrenheit(defaultStationId);
    }

    @CircuitBreaker(name = "noaa-api", fallbackMethod = "fallbackTemperature")
    public Double getCurrentTemperatureFahrenheit(String stationId) {
        String resolvedStation = stationId != null && !stationId.isBlank() ? stationId : defaultStationId;
        try {
            String json = restClient.get()
                    .uri("/stations/{stationId}/observations/latest", resolvedStation)
                    .retrieve()
                    .body(String.class);

            if (json == null) return null;

            // Parse temperature from NOAA GeoJSON response
            // Path: properties.temperature.value (in Celsius)
            int tempIdx = json.indexOf("\"temperature\"");
            if (tempIdx < 0) return null;
            int valueIdx = json.indexOf("\"value\"", tempIdx);
            if (valueIdx < 0) return null;
            int colonIdx = json.indexOf(":", valueIdx);
            if (colonIdx < 0) return null;

            // Extract numeric value after the colon
            int start = colonIdx + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
            if (start >= json.length() || json.charAt(start) == 'n') return null; // null value

            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;

            double celsius = Double.parseDouble(json.substring(start, end));
            return celsius * 9.0 / 5.0 + 32.0;
        } catch (Exception e) {
            log.warn("Failed to fetch NOAA temperature for station {}: {}", resolvedStation, e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unused")
    private Double fallbackTemperature(String stationId, Exception e) {
        log.warn("NOAA circuit breaker open for station {}: {}", stationId, e.getMessage());
        return null;
    }
}
