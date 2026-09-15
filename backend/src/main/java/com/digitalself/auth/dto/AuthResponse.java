package com.digitalself.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds
) {
}
