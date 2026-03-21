package org.fabt.availability.api;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint for backdating availability snapshot timestamps.
 * Gated on @Profile("test") — unreachable in lite, standard, or full profiles.
 * Used by E2E data freshness badge tests to create deterministic STALE data.
 */
@RestController
@RequestMapping("/api/v1/test")
@Profile("test")
public class TestDataController {

    private final JdbcTemplate jdbcTemplate;

    public TestDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/shelters/{id}/backdate")
    public ResponseEntity<String> backdateSnapshot(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "9") int hours) {
        int updated = jdbcTemplate.update(
                "UPDATE bed_availability SET snapshot_ts = NOW() - INTERVAL '1 hour' * ? WHERE shelter_id = ? AND snapshot_ts = (SELECT MAX(snapshot_ts) FROM bed_availability WHERE shelter_id = ?)",
                hours, id, id
        );
        return ResponseEntity.ok("Backdated " + updated + " snapshot(s) by " + hours + " hours");
    }
}
