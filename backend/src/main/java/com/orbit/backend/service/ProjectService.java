package com.orbit.backend.service;

import com.orbit.backend.dto.request.CreateProjectRequest;
import com.orbit.backend.dto.response.ProjectConfigResponse;
import com.orbit.backend.dto.response.ProjectInspectResponse;
import com.orbit.backend.dto.response.ProjectResponse;
import com.orbit.backend.dto.response.ProjectResponse.GitStatus;
import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.entity.Project;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.ProjectRepository;
import com.orbit.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Creates and reads a user's projects and the folders behind them. Every lookup is scoped to the calling user. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectPathResolver pathResolver;
    private final GitService gitService;
    private final ProjectFileService fileService;

    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        String name = request.projectName().trim();
        if (name.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Project name is required");
        }
        ProjectPathResolver.Resolved where = pathResolver.resolve(request.location());

        for (Project existing : projectRepository.findByUser_UserIdOrderByCreatedAtDesc(userId)) {
            if (ProjectPathResolver.sameLocation(existing.getLocation(), where.location())) {
                throw new ApiException(ErrorCode.PROJECT_ALREADY_EXISTS);
            }
        }

        Path folder = where.path();
        boolean folderCreated = false;
        if (Files.exists(folder)) {
            if (!Files.isDirectory(folder)) {
                throw new ApiException(ErrorCode.PROJECT_LOCATION_NOT_DIRECTORY);
            }
        } else {
            try {
                Files.createDirectories(folder);
                folderCreated = true;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not create project folder {}: {}", folder, e.toString());
                throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID,
                        "The project folder could not be created - check the location and its permissions");
            }
        }

        GitStatus gitStatus = GitStatus.SKIPPED;
        if (Boolean.TRUE.equals(request.initGit())) {
            if (gitService.hasRepository(folder)) {
                gitStatus = GitStatus.ALREADY_EXISTS; // already a repository: nothing to initialize
            } else {
                try {
                    gitService.init(folder);
                    gitStatus = GitStatus.INITIALIZED;
                } catch (IOException | RuntimeException e) {
                    log.error("git init failed in {}", folder, e);
                    throw new ApiException(ErrorCode.PROJECT_GIT_INIT_FAILED);
                }
            }
        }

        Project project = new Project();
        project.setProjectName(name);
        project.setLocation(where.location());
        project.setProjectType(request.projectType());
        project.setDescription(blankToNull(request.description()));
        project.setUser(userRepository.getReferenceById(userId));
        try {
            project = projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.PROJECT_ALREADY_EXISTS); // lost a race with a concurrent create
        }
        log.info("User {} created project '{}' ({}) at {} [git: {}]", userId, name, project.getProjectId(), folder, gitStatus);
        return ProjectResponse.from(project, true, gitService.hasRepository(folder), gitStatus, folderCreated);
    }

    public List<ProjectResponse> list(UUID userId) {
        return projectRepository.findByUser_UserIdOrderByCreatedAtDesc(userId).stream().map(this::describe).toList();
    }

    public ProjectResponse get(UUID userId, UUID projectId) {
        return describe(find(userId, projectId));
    }

    /**
     * Removes the project from ORBIT. The caller must repeat the exact project name, so a stray request cannot delete
     * anything. Only the database row goes away: the folder and its files on the user's computer are left untouched.
     */
    @Transactional
    public void delete(UUID userId, UUID projectId, String confirmName) {
        Project project = find(userId, projectId);
        if (confirmName == null || !confirmName.equals(project.getProjectName())) {
            throw new ApiException(ErrorCode.PROJECT_NAME_MISMATCH);
        }
        projectRepository.delete(project);
        log.info("User {} removed project '{}' ({}) from ORBIT; its folder {} was not touched", userId,
                project.getProjectName(), projectId, project.getLocation());
    }

    public ProjectTreeResponse tree(UUID userId, UUID projectId, String relativePath) {
        Project project = find(userId, projectId);
        return fileService.list(pathResolver.resolve(project.getLocation()).path(), relativePath);
    }

    public ProjectInspectResponse inspect(String location) {
        if (location == null || location.isBlank()) {
            return new ProjectInspectResponse(false, "Enter a location", false, false);
        }
        ProjectPathResolver.Resolved where;
        try {
            where = pathResolver.resolve(location);
        } catch (ApiException e) {
            return new ProjectInspectResponse(false, e.getMessage(), false, false);
        }
        Path folder = where.path();
        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            return new ProjectInspectResponse(false, "That location is a file, not a folder", true, false);
        }
        boolean exists = Files.isDirectory(folder);
        return new ProjectInspectResponse(true, exists ? "Existing folder will be used" : "Folder will be created",
                exists, exists && gitService.hasRepository(folder));
    }

    public ProjectConfigResponse config() {
        return new ProjectConfigResponse(pathResolver.visibleRoot(), pathResolver.visibleRoots());
    }

    private Project find(UUID userId, UUID projectId) {
        // someone else's project looks exactly like a missing one
        return projectRepository.findByProjectIdAndUser_UserId(projectId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROJECT_NOT_FOUND));
    }

    private ProjectResponse describe(Project project) {
        boolean available = false;
        boolean git = false;
        try {
            Path folder = pathResolver.resolve(project.getLocation()).path();
            available = Files.isDirectory(folder);
            git = available && gitService.hasRepository(folder);
        } catch (ApiException ignored) {
            // location no longer maps (e.g. the mount changed): reported as unavailable
        }
        return ProjectResponse.from(project, available, git, null, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
