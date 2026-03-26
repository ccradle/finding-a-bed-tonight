package org.fabt.auth.api;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn
) {
}
