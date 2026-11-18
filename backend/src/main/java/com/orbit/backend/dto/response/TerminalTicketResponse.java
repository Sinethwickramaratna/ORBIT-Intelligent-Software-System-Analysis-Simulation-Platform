package com.orbit.backend.dto.response;

/**
 * A one-time ticket for the ORBIT terminal helper. {@code expiresAt} is in epoch milliseconds; {@code cwd} is the
 * project folder the terminal starts in, as stored for the project.
 */
public record TerminalTicketResponse(String ticket, long expiresAt, String cwd) {
}
