package org.fabt.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeploymentTierDetector {

    private static final Logger log = LoggerFactory.getLogger(DeploymentTierDetector.class);

    private final DeploymentTier tier;

    public DeploymentTierDetector(@Value("${fabt.deployment-tier:lite}") String tierName) {
        DeploymentTier resolved;
        try {
            resolved = DeploymentTier.valueOf(tierName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown deployment tier '{}' — defaulting to LITE", tierName);
            resolved = DeploymentTier.LITE;
        }
        this.tier = resolved;
    }

    @PostConstruct
    void logTier() {
        if (tier == DeploymentTier.LITE) {
            log.warn("Deployment tier: LITE — PostgreSQL only, no Redis or Kafka");
        } else {
            log.info("Deployment tier: {}", tier);
        }
    }

    public DeploymentTier getTier() {
        return tier;
    }
}
