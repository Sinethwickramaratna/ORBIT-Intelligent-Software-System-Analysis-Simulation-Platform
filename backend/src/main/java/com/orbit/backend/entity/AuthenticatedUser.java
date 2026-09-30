package com.orbit.backend.entity;

import java.util.UUID;

/** Principal placed in the SecurityContext after a valid access token is presented. */
public record AuthenticatedUser(UUID userId, String username) {
}
