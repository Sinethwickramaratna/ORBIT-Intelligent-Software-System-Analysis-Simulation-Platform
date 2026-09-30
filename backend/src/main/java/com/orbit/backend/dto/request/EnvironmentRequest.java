package com.orbit.backend.dto.request;

/** First-run environment values. Every field is optional; only the ones that are still missing may be sent. */
public record EnvironmentRequest(String secretKey, String dbUsername, String dbPassword) {
}
