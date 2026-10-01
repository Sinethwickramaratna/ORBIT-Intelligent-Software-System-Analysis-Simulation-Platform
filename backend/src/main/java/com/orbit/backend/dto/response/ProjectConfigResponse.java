package com.orbit.backend.dto.response;

/** {@code root} is the folder (on the user's computer) that project locations must be inside; null = anywhere. */
public record ProjectConfigResponse(String root) {
}
