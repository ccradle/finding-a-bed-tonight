package org.fabt.dataimport;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests file upload size limit enforcement.
 * Uses @TestPropertySource to set a small limit (256KB) so we can test
 * rejection without creating a 10MB+ payload.
 */
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=256KB",
        "spring.servlet.multipart.max-request-size=256KB"
})
class ImportFileSizeLimitTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupCocAdminUser();
    }

    @Test
    void test_fileExceedingSizeLimit_returns413() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // Generate a CSV payload larger than 256KB (~300KB of repeated rows)
        StringBuilder csv = new StringBuilder("name,address,city,state,zip,phone\n");
        String row = "Test Shelter With A Reasonably Long Name For Padding,1234 Some Street Address That Is Also Long,Raleigh,NC,27601,919-555-0100\n";
        while (csv.length() < 300_000) {
            csv.append(row);
        }

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setBearerAuth(headers.getFirst(HttpHeaders.AUTHORIZATION).replace("Bearer ", ""));
        multipartHeaders.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);

        org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(csv.toString().getBytes()) {
            @Override
            public String getFilename() {
                return "large-file.csv";
            }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/211",
                HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders),
                String.class
        );

        // Spring 6.2+ uses CONTENT_TOO_LARGE (the updated HTTP 413 name)
        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }
}
