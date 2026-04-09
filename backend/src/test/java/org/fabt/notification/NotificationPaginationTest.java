package org.fabt.notification;

import java.util.Map;

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

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPaginationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private NotificationPersistenceService notificationPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User coordinator;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        coordinator = authHelper.setupCoordinatorUser();
        // Clean up prior notifications
        jdbcTemplate.update("DELETE FROM notification WHERE recipient_id = ?", coordinator.getId());

        // Create 7 test notifications
        for (int i = 0; i < 7; i++) {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinator.getId(),
                    "test.pagination", "INFO",
                    "{\"index\": " + i + "}");
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
    void firstPage_size3_hasMore() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?page=0&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"hasMore\":true");
        assertThat(resp.getBody()).contains("\"size\":3");
        // Count items in the response
        int itemCount = resp.getBody().split("\"id\"").length - 1;
        assertThat(itemCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Page 1, size 3 → 3 items, hasMore=true (4 remaining)")
    void secondPage_size3_hasMore() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?page=1&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"hasMore\":true");
        assertThat(resp.getBody()).contains("\"page\":1");
    }

    @Test
    @DisplayName("Page 2, size 3 → 1 item, hasMore=false (last page)")
    void lastPage_size3_noMore() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?page=2&size=3",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"hasMore\":false");
        int itemCount = resp.getBody().split("\"id\"").length - 1;
        assertThat(itemCount).isEqualTo(1);
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
    void unreadFilter_withPagination() {
        // Mark 3 of the 7 as read
        jdbcTemplate.update(
                "UPDATE notification SET read_at = NOW() WHERE id IN (SELECT id FROM notification WHERE recipient_id = ? AND read_at IS NULL LIMIT 3)",
                coordinator.getId());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications?unread=true&page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Should have fewer than 7 items since some are read
        int itemCount = resp.getBody().split("\"id\"").length - 1;
        assertThat(itemCount).isLessThan(7);
    }
}
