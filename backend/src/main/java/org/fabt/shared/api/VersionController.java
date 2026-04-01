package org.fabt.shared.api;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnResource(resources = "classpath:META-INF/build-info.properties")
public class VersionController {

    private final BuildProperties buildProperties;

    public VersionController(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        String full = buildProperties.getVersion();
        String[] parts = full.split("\\.");
        String display = parts.length >= 2 ? parts[0] + "." + parts[1] : full;
        return ResponseEntity.ok(Map.of("version", display));
    }
}
