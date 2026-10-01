package com.orbit.backend.dto.response;

/** Live feedback for the Location field of the create dialog. */
public record ProjectInspectResponse(boolean valid, String message, boolean exists, boolean gitRepository) {
}
