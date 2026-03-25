package org.fabt.dataimport.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.fabt.dataimport.service.ShelterImportService.ShelterImportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses HSDS 3.0 (Human Services Data Specification) JSON into shelter import rows.
 * HSDS structure: { "organizations": [...], "services": [...], "locations": [...] }
 * Organizations are joined to locations and services by organization_id.
 */
@Service
public class HsdsImportAdapter {

    private static final Logger log = LoggerFactory.getLogger(HsdsImportAdapter.class);

    private final ObjectMapper objectMapper;

    public HsdsImportAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse HSDS 3.0 JSON content into a list of shelter import rows.
     *
     * @param jsonContent raw JSON string in HSDS 3.0 format
     * @return list of shelter import rows
     * @throws IllegalArgumentException if the JSON structure is invalid or missing required HSDS fields
     */
    public List<ShelterImportRow> parseHsds(String jsonContent) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonContent);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("HSDS JSON must be a JSON object");
        }

        // Parse organizations — required
        JsonNode organizationsNode = root.get("organizations");
        if (organizationsNode == null || !organizationsNode.isArray()) {
            throw new IllegalArgumentException("HSDS JSON must contain an 'organizations' array");
        }

        // Parse locations — optional but expected
        JsonNode locationsNode = root.get("locations");
        Map<String, JsonNode> locationsByOrgId = new HashMap<>();
        if (locationsNode != null && locationsNode.isArray()) {
            for (JsonNode location : locationsNode) {
                String orgId = textOrNull(location, "organization_id");
                if (orgId != null) {
                    locationsByOrgId.put(orgId, location);
                }
            }
        }

        // Parse services — optional
        JsonNode servicesNode = root.get("services");
        Map<String, JsonNode> servicesByOrgId = new HashMap<>();
        if (servicesNode != null && servicesNode.isArray()) {
            for (JsonNode service : servicesNode) {
                String orgId = textOrNull(service, "organization_id");
                if (orgId != null) {
                    servicesByOrgId.put(orgId, service);
                }
            }
        }

        List<ShelterImportRow> rows = new ArrayList<>();

        for (JsonNode org : organizationsNode) {
            String orgId = textOrNull(org, "id");
            String name = textOrNull(org, "name");

            if (name == null || name.isBlank()) {
                log.warn("Skipping HSDS organization with no name, id={}", orgId);
                continue;
            }

            // Extract address from matching location
            String addressStreet = null;
            String addressCity = null;
            String addressState = null;
            String addressZip = null;
            Double latitude = null;
            Double longitude = null;
            String phone = null;

            if (orgId != null) {
                JsonNode location = locationsByOrgId.get(orgId);
                if (location != null) {
                    // HSDS locations may have nested physical_address or flat fields
                    JsonNode physAddr = location.get("physical_address");
                    if (physAddr != null && physAddr.isArray() && !physAddr.isEmpty()) {
                        JsonNode addr = physAddr.get(0);
                        addressStreet = textOrNull(addr, "address_1");
                        addressCity = textOrNull(addr, "city");
                        addressState = textOrNull(addr, "state_province");
                        addressZip = textOrNull(addr, "postal_code");
                    } else if (physAddr != null && physAddr.isObject()) {
                        addressStreet = textOrNull(physAddr, "address_1");
                        addressCity = textOrNull(physAddr, "city");
                        addressState = textOrNull(physAddr, "state_province");
                        addressZip = textOrNull(physAddr, "postal_code");
                    } else {
                        // Try flat fields on location
                        addressStreet = textOrNull(location, "address_1");
                        if (addressStreet == null) {
                            addressStreet = textOrNull(location, "address");
                        }
                        addressCity = textOrNull(location, "city");
                        addressState = textOrNull(location, "state_province");
                        if (addressState == null) {
                            addressState = textOrNull(location, "state");
                        }
                        addressZip = textOrNull(location, "postal_code");
                        if (addressZip == null) {
                            addressZip = textOrNull(location, "zip");
                        }
                    }

                    latitude = doubleOrNull(location, "latitude");
                    longitude = doubleOrNull(location, "longitude");

                    // Phone from location phones array
                    JsonNode phones = location.get("phones");
                    if (phones != null && phones.isArray() && !phones.isEmpty()) {
                        phone = textOrNull(phones.get(0), "number");
                    }
                }

                // Also check for phone on organization
                if (phone == null) {
                    JsonNode orgPhones = org.get("phones");
                    if (orgPhones != null && orgPhones.isArray() && !orgPhones.isEmpty()) {
                        phone = textOrNull(orgPhones.get(0), "number");
                    }
                }

                // Check service for additional details
                JsonNode service = servicesByOrgId.get(orgId);
                if (service != null && phone == null) {
                    JsonNode svcPhones = service.get("phones");
                    if (svcPhones != null && svcPhones.isArray() && !svcPhones.isEmpty()) {
                        phone = textOrNull(svcPhones.get(0), "number");
                    }
                }
            }

            ShelterImportRow row = new ShelterImportRow(
                    name,
                    addressStreet,
                    addressCity,
                    addressState,
                    addressZip,
                    phone,
                    latitude,
                    longitude,
                    null,   // dvShelter — not in HSDS standard
                    null,   // sobrietyRequired
                    null,   // idRequired
                    null,   // referralRequired
                    null,   // petsAllowed
                    null,   // wheelchairAccessible
                    null,   // curfewTime
                    null,   // maxStayDays
                    null,   // populationTypesServed
                    null    // capacityByType
            );

            rows.add(row);
        }

        return rows;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isNumber()) {
            return child.asDouble();
        }
        if (child.isTextual()) {
            try {
                return Double.parseDouble(child.asText());
            } catch (NumberFormatException e) {
                log.debug("Unparseable numeric value during HSDS import: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }
}
