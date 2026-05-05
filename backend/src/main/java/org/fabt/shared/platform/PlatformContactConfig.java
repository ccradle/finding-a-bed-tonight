package org.fabt.shared.platform;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link PlatformContactProperties} and emits a startup INFO log
 * confirming presence/absence of the property — operators grep this line
 * post-deploy to verify {@code FABT_PLATFORM_CONTACT_EMAIL} is wired
 * without echoing the literal address to logs (which would defeat the
 * {@code feedback_no_ip_in_repo}-style anti-leak posture).
 */
@Configuration
@EnableConfigurationProperties(PlatformContactProperties.class)
public class PlatformContactConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformContactConfig.class);

    private final PlatformContactProperties properties;

    public PlatformContactConfig(PlatformContactProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logStartupState() {
        boolean present = properties.contactEmail() != null && !properties.contactEmail().isBlank();
        LOG.info("platform contact email configured: {}", present ? "present" : "absent");
    }
}
