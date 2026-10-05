package com.orbit.backend.dto.response;

import java.util.List;

/** What "Check Repository" found. When {@code found} is false only {@code message} is meaningful. */
public record RepositoryInspectResponse(
        boolean found,
        String message,
        String repository,
        String owner,
        String defaultBranch,
        int branchCount,
        String visibility,
        List<String> branches) {

    public static RepositoryInspectResponse notFound(String message) {
        return new RepositoryInspectResponse(false, message, null, null, null, 0, null, List.of());
    }
}
