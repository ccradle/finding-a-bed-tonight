package org.fabt.hmis.adapter;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Eccovia ClientTrack HMIS adapter — pushes JSON inventory via REST API.
 */
@Component
public class ClientTrackAdapter implements HmisVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientTrackAdapter.class);
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public ClientTrackAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public void push(List<HmisInventoryRecord> records, HmisVendorConfig config) throws Exception {
        String json = objectMapper.writeValueAsString(records);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "ApiKey " + config.apiKeyEncrypted()); // TODO: decrypt
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        String url = config.baseUrl() + "/api/inventory";
        log.info("Pushing {} inventory records to ClientTrack at {}", records.size(), config.baseUrl());

        restTemplate.postForEntity(url, entity, String.class);
    }

    @Override
    public String vendorType() {
        return "CLIENTTRACK";
    }
}
