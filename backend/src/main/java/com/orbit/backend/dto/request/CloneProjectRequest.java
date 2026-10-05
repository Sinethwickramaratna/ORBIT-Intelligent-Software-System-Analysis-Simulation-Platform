package com.orbit.backend.dto.request;

import com.orbit.backend.entity.ProjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Clone Repository" in the Clone Git Repository window. Only what a public clone needs: the authentication and
 * advanced options of the window are not sent yet.
 */
public record CloneProjectRequest(
        @NotBlank(message = "Repository URL is required")
        @Size(max = 2048, message = "Repository URL is too long")
        String repositoryUrl,

        @NotBlank(message = "Branch is required")
        @Size(max = 255, message = "Branch name is too long")
        String branch,

        /** The folder the repository is cloned INTO; the repository gets its own sub-folder there. */
        @NotBlank(message = "Clone location is required")
        @Size(max = 1024, message = "Clone location must be at most 1024 characters")
        String location,

        @NotBlank(message = "Project name is required")
        @Size(max = 150, message = "Project name must be at most 150 characters")
        String projectName,

        @NotNull(message = "Project type is required")
        ProjectType projectType,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description) {
}
