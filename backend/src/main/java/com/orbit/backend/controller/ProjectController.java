package com.orbit.backend.controller;

import com.orbit.backend.dto.request.CloneInspectRequest;
import com.orbit.backend.dto.request.CloneProjectRequest;
import com.orbit.backend.dto.request.CreateFolderRequest;
import com.orbit.backend.dto.request.CreateProjectRequest;
import com.orbit.backend.dto.response.FolderBrowseResponse;
import com.orbit.backend.service.FolderBrowseService;
import com.orbit.backend.dto.response.ProjectConfigResponse;
import com.orbit.backend.dto.response.ProjectInspectResponse;
import com.orbit.backend.dto.response.ProjectResponse;
import com.orbit.backend.dto.response.ProjectScanResponse;
import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.dto.response.RepositoryInspectResponse;
import com.orbit.backend.entity.AuthenticatedUser;
import com.orbit.backend.service.ProjectScanService;
import com.orbit.backend.service.ScanProgressTracker;
import com.orbit.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final FolderBrowseService folderBrowseService;
    private final ProjectScanService scanService;

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

    /** "Check Repository" of the Clone window: owner, default branch, branches, visibility of a public repository. */
    @PostMapping("/clone/inspect")
    public RepositoryInspectResponse inspectRepository(@Valid @RequestBody CloneInspectRequest request) {
        return projectService.inspectRepository(request.repositoryUrl());
    }

    /** Clones the repository and registers it as a project. Returns once the clone is complete. */
    @PostMapping("/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse cloneRepository(@Valid @RequestBody CloneProjectRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.cloneProject(principal.userId(), request);
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

    /** Sub-folders of {@code path} (empty = the starting folder) for the "choose a folder" window. */
    @GetMapping("/browse")
    public FolderBrowseResponse browse(@RequestParam(name = "path", required = false) String path) {
        return folderBrowseService.browse(path);
    }

    /** "New Folder" inside the chooser. */
    @PostMapping("/browse/folder")
    @ResponseStatus(HttpStatus.CREATED)
    public FolderBrowseResponse createFolder(@Valid @RequestBody CreateFolderRequest request) {
        return folderBrowseService.browse(folderBrowseService.createFolder(request.parent(), request.name()));
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

    /** The Scan button: reads the project folder again (it may have changed) and stores a new scan. */
    @PostMapping("/{projectId}/scan")
    public ProjectScanResponse scan(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return scanService.scan(principal.userId(), projectId);
    }

    /** The newest stored scan of the project; 204 No Content when it has never been scanned. */
    @GetMapping("/{projectId}/scan")
    public ResponseEntity<ProjectScanResponse> latestScan(@PathVariable UUID projectId,
                                                          @AuthenticationPrincipal AuthenticatedUser principal) {
        return scanService.latest(principal.userId(), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** How far a running scan is: {@code {active, phase, percent}}; the UI polls it to draw the progress bar. */
    @GetMapping("/{projectId}/scan/progress")
    public ScanProgressTracker.Progress scanProgress(@PathVariable UUID projectId,
                                                     @AuthenticationPrincipal AuthenticatedUser principal) {
        return scanService.progress(principal.userId(), projectId);
    }

    /**
     * Removes the project from ORBIT (its files on disk are kept). {@code confirmName} must equal the project name.
     */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId,
                       @RequestParam(name = "confirmName", required = false) String confirmName,
                       @AuthenticationPrincipal AuthenticatedUser principal) {
        projectService.delete(principal.userId(), projectId, confirmName);
    }
}
