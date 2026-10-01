package com.orbit.backend.dto.request;

import java.util.List;

/**
 * First-run environment values. Every field is optional; only the ones that are still missing may be sent.
 * {@code folders} are the drives/folders ORBIT may open (Docker mounts), saved as ORBIT_MOUNT_n.
 */
public record EnvironmentRequest(String secretKey, String dbUsername, String dbPassword, List<String> folders) {
}
