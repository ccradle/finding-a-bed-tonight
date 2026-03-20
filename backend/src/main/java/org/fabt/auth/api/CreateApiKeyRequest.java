package org.fabt.auth.api;

import java.util.UUID;

public record CreateApiKeyRequest(
        UUID shelterId,
        String label
) {
}
