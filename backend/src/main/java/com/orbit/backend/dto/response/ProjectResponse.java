package com.orbit.backend.dto.response;

import com.orbit.backend.entity.Project;
import com.orbit.backend.entity.ProjectType;

import java.time.Instant;
import java.util.UUID;

/**
 * A project as the UI needs it. {@code folderAvailable} / {@code gitRepository} are looked up live on disk;
 * {@code gitStatus} and {@code folderCreated} are only filled in by the create call.
 */
public record ProjectResponse(
        UUID projectId,
        String projectName,
        String location,
        ProjectType projectType,
        String projectTypeLabel,
        String description,
        UUID userId,
        Instant createdAt,
        boolean folderAvailable,
        boolean gitRepository,
        GitStatus gitStatus,
        Boolean folderCreated) {

    public enum GitStatus { INITIALIZED, ALREADY_EXISTS, SKIPPED }

    public static ProjectResponse from(Project p, boolean folderAvailable, boolean gitRepository,
                                       GitStatus gitStatus, Boolean folderCreated) {
        return new ProjectResponse(p.getProjectId(), p.getProjectName(), p.getLocation(), p.getProjectType(),
                p.getProjectType().getLabel(), p.getDescription(), p.getUserId(), p.getCreatedAt(),
                folderAvailable, gitRepository, gitStatus, folderCreated);
    }
}
