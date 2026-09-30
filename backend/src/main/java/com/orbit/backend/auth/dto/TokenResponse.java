package com.orbit.backend.auth.dto;

import com.orbit.backend.user.dto.UserResponse;

import java.time.Instant;

/** {@code user} is only populated by login. */
public record TokenResponse(String tokenType, String accessToken, Instant accessTokenExpiresAt,
                            String refreshToken, Instant refreshTokenExpiresAt, UserResponse user) {
}
