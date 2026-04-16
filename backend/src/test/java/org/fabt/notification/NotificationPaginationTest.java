package org.fabt.notification;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.NotificationPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.fabt.shared.web.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPaginationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private NotificationPersistenceService notificationPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User coordinator;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        coordinator = authHelper.setupCoordinatorUser();
        // Clean up prior notifications
        jdbcTemplate.update("DELETE FROM notification WHERE recipient_id = ?", coordinator.getId());

        // Create 7 test notifications (D11: wrap in TenantContext for send())
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            TenantContext.runWithContext(authHelper.getTestTenantId(), false, () ->
                    notificationPersistenceService.send(
                            coordinator.getId(),
                            "test.pagination", "INFO",
                            "{\"index\": " + idx + "}"));
        }
    }

    @Test
    @DisplayName("Default pagination: page=0, size=20, returns all 7")
    void defaultPagination_returnsAll() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"hasMore\":false");
        assertThat(resp.getBody()).contains("\"page\":0");
        assertThat(resp.getBody()).contains("\"size\":20");
    }

    @Test
    @DisplayName("Page 0, size 3 → 3 items, hasMore=true")
    void firstPage_size3_hasMore() throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?page=0&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var json = objectMapper.readValue(resp.getBody(), Map.class);
        assertThat(json.get("hasMore")).isEqualTo(true);
        assertThat(json.get("size")).isEqualTo(3);
        assertThat((List<?>) json.get("items")).hasSize(3);
    }

    @Test
    @DisplayName("Page 0 and page 1 return different items (no overlap)")
    void pages_doNotOverlap() throws Exception {
        ResponseEntity<String> resp0 = restTemplate.exchange(
                "/api/v1/notifications?page=0&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        ResponseEntity<String> resp1 = restTemplate.exchange(
                "/api/v1/notifications?page=1&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        var json0 = objectMapper.readValue(resp0.getBody(), Map.class);
        var json1 = objectMapper.readValue(resp1.getBody(), Map.class);
        List<Map<String, Object>> items0 = (List<Map<String, Object>>) json0.get("items");
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) json1.get("items");

        var ids0 = items0.stream().map(i -> i.get("id").toString()).toList();
        var ids1 = items1.stream().map(i -> i.get("id").toString()).toList();
        // No overlap between pages
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
    }

    @Test
    @DisplayName("Page 2, size 3 → 1 item, hasMore=false (last page)")
    void lastPage_size3_noMore() throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?page=2&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var json = objectMapper.readValue(resp.getBody(), Map.class);
        assertThat(json.get("hasMore")).isEqualTo(false);
        assertThat((List<?>) json.get("items")).hasSize(1);
    }

    @Test
    @DisplayName("Size 0 clamped to 1, size 100 clamped to 50")
    void sizeClamping() {
        // size=0 → clamped to 1
        ResponseEntity<String> resp0 = restTemplate.exchange(
                "/api/v1/notifications?size=0",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(resp0.getBody()).contains("\"size\":1");

        // size=100 → clamped to 50
        ResponseEntity<String> resp100 = restTemplate.exchange(
                "/api/v1/notifications?size=100",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(resp100.getBody()).contains("\"size\":50");
    }

    @Test
    @DisplayName("Unread filter with pagination")
    void unreadFilter_withPagination() throws Exception {
        // Mark 3 of the 7 as read (ORDER BY ensures deterministic selection)
        jdbcTemplate.update(
                "UPDATE notification SET read_at = NOW() WHERE id IN (SELECT id FROM notification WHERE recipient_id = ? AND read_at IS NULL ORDER BY created_at LIMIT 3)",
                coordinator.getId());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?unread=true&page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var json = objectMapper.readValue(resp.getBody(), Map.class);
        List<?> items = (List<?>) json.get("items");
        // 7 created, 3 marked read → 4 unread
        assertThat(items).hasSize(4);
    }
}
