package org.fabt.auth.api;

import java.util.UUID;

public record ApiKeyCreateResponse(
        UUID id,
        String plaintextKey,
        String suffix
) {
}
