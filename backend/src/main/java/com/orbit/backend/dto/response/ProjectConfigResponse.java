package com.orbit.backend.dto.response;

import java.util.List;

/**
 * {@code roots} are the folders/drives (as on the user's computer) that project locations must be inside; empty =
 * anywhere. {@code root} is the first of them (null when empty).
 */
public record ProjectConfigResponse(String root, List<String> roots) {
}
