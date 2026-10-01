package com.orbit.backend.controller;

import com.orbit.backend.dto.request.CreateProjectRequest;
import com.orbit.backend.dto.response.ProjectConfigResponse;
import com.orbit.backend.dto.response.ProjectInspectResponse;
import com.orbit.backend.dto.response.ProjectResponse;
import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.entity.AuthenticatedUser;
import com.orbit.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Projects of the signed-in user. Every endpoint requires a valid access token. */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.create(principal.userId(), request);
    }

    /** Which folder project locations must be inside (null = anywhere). */
    @GetMapping("/config")
    public ProjectConfigResponse config() {
        return projectService.config();
    }

    /** Live check of the Location field: valid? existing folder? already a git repository? */
    @GetMapping("/inspect")
    public ProjectInspectResponse inspect(@RequestParam(name = "location", required = false) String location) {
        return projectService.inspect(location);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.get(principal.userId(), projectId);
    }

    /** One level of the project folder; {@code path} is relative to the project root (empty = the root). */
    @GetMapping("/{projectId}/tree")
    public ProjectTreeResponse tree(@PathVariable UUID projectId,
                                    @RequestParam(name = "path", required = false, defaultValue = "") String path,
                                    @AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.tree(principal.userId(), projectId, path);
    }
}
