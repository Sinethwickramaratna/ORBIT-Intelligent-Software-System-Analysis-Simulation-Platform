package com.orbit.backend.auth;

import java.util.UUID;

/** Principal placed in the SecurityContext after a valid access token is presented. */
public record AuthenticatedUser(UUID userId, String username) {
}
