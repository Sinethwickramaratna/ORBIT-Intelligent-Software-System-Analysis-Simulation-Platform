package com.orbit.backend.dto.response;

import java.time.Instant;

/** {@code user} is only populated by login. */
public record TokenResponse(String tokenType, String accessToken, Instant accessTokenExpiresAt,
                            String refreshToken, Instant refreshTokenExpiresAt, UserResponse user) {
}
