package com.orbit.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Check Repository" in the Clone Git Repository window. */
public record CloneInspectRequest(
        @NotBlank(message = "Repository URL is required")
        @Size(max = 2048, message = "Repository URL is too long")
        String repositoryUrl) {
}
