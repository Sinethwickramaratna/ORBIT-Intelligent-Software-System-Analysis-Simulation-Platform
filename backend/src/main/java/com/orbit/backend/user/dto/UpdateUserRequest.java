package com.orbit.backend.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Both fields are optional; only supplied fields are changed. */
public record UpdateUserRequest(
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "Username may contain letters, digits, '.', '_' and '-'")
        String userName,

        @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        String password) {
}
