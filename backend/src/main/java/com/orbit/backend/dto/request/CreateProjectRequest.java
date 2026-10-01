package com.orbit.backend.dto.request;

import com.orbit.backend.entity.ProjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(max = 150, message = "Project name must be at most 150 characters")
        String projectName,

        @NotBlank(message = "Location is required")
        @Size(max = 1024, message = "Location must be at most 1024 characters")
        String location,

        @NotNull(message = "Project type is required")
        ProjectType projectType,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        /** Run {@code git init} in the folder, unless it already is a git repository. */
        Boolean initGit) {
}
