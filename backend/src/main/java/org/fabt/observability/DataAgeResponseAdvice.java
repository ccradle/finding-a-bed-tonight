package org.fabt.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.fabt.shelter.api.ShelterController;
import org.fabt.shelter.api.ShelterDetailResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class DataAgeResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getContainingClass().equals(ShelterController.class);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ShelterDetailResponse detailResponse) {
            return enrichWithDataAge(detailResponse);
        }

        if (body instanceof Map<?, ?> mapBody) {
            return enrichMapWithDataAge(mapBody);
        }

        return body;
    }

    private Map<String, Object> enrichWithDataAge(ShelterDetailResponse detailResponse) {
        Instant updatedAt = detailResponse.shelter() != null ? detailResponse.shelter().updatedAt() : null;
        Long ageSeconds = calculateAgeSeconds(updatedAt);
        DataFreshness freshness = DataFreshness.fromAgeSeconds(ageSeconds);

        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("shelter", detailResponse.shelter());
        enriched.put("constraints", detailResponse.constraints());
        enriched.put("capacities", detailResponse.capacities());
        enriched.put("data_age_seconds", ageSeconds);
        enriched.put("data_freshness", freshness.name());
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichMapWithDataAge(Map<?, ?> mapBody) {
        // HSDS export maps may contain an "updated_at" or similar timestamp field
        Instant updatedAt = extractUpdatedAt(mapBody);
        Long ageSeconds = calculateAgeSeconds(updatedAt);
        DataFreshness freshness = DataFreshness.fromAgeSeconds(ageSeconds);

        Map<String, Object> enriched = new LinkedHashMap<>((Map<String, Object>) mapBody);
        enriched.put("data_age_seconds", ageSeconds);
        enriched.put("data_freshness", freshness.name());
        return enriched;
    }

    private Instant extractUpdatedAt(Map<?, ?> map) {
        Object value = map.get("updated_at");
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String str) {
            try {
                return Instant.parse(str);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Long calculateAgeSeconds(Instant updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        return Duration.between(updatedAt, Instant.now()).getSeconds();
    }
}
