package com.orbit.backend.dto.response;

import java.util.List;

/** One level of a project's folder: {@code path} is relative to the project root ("" = the root), '/'-separated. */
public record ProjectTreeResponse(String path, List<Entry> entries, boolean truncated) {

    public enum Kind { FOLDER, FILE }

    /** {@code size} is null for folders; {@code hasChildren} is only meaningful for folders. */
    public record Entry(String name, String path, Kind kind, Long size, boolean hasChildren) {
    }
}
