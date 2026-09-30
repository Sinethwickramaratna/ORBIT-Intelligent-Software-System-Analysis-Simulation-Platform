package com.orbit.backend.user.dto;

import com.orbit.backend.user.User;

import java.time.Instant;
import java.util.UUID;

/** Public view of a user - never exposes the password hash. */
public record UserResponse(UUID userId, String userName, Instant createdAt, Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getUserId(), user.getUserName(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
