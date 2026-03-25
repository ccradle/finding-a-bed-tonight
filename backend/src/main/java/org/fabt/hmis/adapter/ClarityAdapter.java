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
 * Bitfocus Clarity HMIS adapter — pushes JSON inventory via REST API.
 */
@Component
public class ClarityAdapter implements HmisVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClarityAdapter.class);
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public ClarityAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public void push(List<HmisInventoryRecord> records, HmisVendorConfig config) throws Exception {
        String json = objectMapper.writeValueAsString(records);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", config.apiKeyEncrypted()); // TODO: decrypt in production
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        String url = config.baseUrl() + "/inventory";
        log.info("Pushing {} inventory records to Clarity at {}", records.size(), config.baseUrl());

        restTemplate.postForEntity(url, entity, String.class);
    }

    @Override
    public String vendorType() {
        return "CLARITY";
    }
}
