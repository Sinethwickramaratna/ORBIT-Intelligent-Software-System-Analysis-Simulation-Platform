package com.orbit.backend.controller;

import com.orbit.backend.dto.response.DependencyCheckResponse;
import com.orbit.backend.dto.response.DependencySetupProgress;
import com.orbit.backend.entity.AuthenticatedUser;
import com.orbit.backend.service.ProjectDependencyService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dependency Check and Setup Dependencies of the signed-in user's projects (Architecture Reconstruction, step 1). */
@RestController
@RequestMapping("/api/projects/{projectId}/dependencies")
@RequiredArgsConstructor
public class ProjectDependencyController {

    private final ProjectDependencyService dependencyService;

    /** The stored check (made automatically when the project was added); 204 when the project was never checked. */
    @GetMapping
    public ResponseEntity<DependencyCheckResponse> latest(@PathVariable UUID projectId,
                                                          @AuthenticationPrincipal AuthenticatedUser principal) {
        return dependencyService.latest(principal.userId(), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The Dependency Check button: checks the project folder now and stores the result. */
    @PostMapping("/check")
    public DependencyCheckResponse check(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return dependencyService.check(principal.userId(), projectId);
    }

    /** How far the running (or the last) Dependency Check is; the UI polls it while the check request is open. */
    @GetMapping("/check/progress")
    public DependencySetupProgress checkProgress(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return dependencyService.checkProgress(principal.userId(), projectId);
    }

    /** Setup Dependencies (after the user confirmed): starts the downloads; 409 when a setup is already running. */
    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DependencySetupProgress setup(@PathVariable UUID projectId,
                                         @RequestBody(required = false) SetupRequest request,
                                         @AuthenticationPrincipal AuthenticatedUser principal) {
        boolean update = request == null || request.updateManifests() == null || request.updateManifests();
        boolean skipUnused = request == null || request.skipUnused() == null || request.skipUnused();
        return dependencyService.startSetup(principal.userId(), projectId, update, skipUnused);
    }

    /**
     * Options of Setup Dependencies. {@code updateManifests} (default true): when a pinned version cannot be installed,
     * install a newer one that can and update the requirements file. {@code skipUnused} (default true): do not download
     * declared dependencies the source code never uses.
     */
    public record SetupRequest(Boolean updateManifests, Boolean skipUnused) {
    }

    /** How far the setup is; the UI polls it until {@code state} is no longer RUNNING. */
    @GetMapping("/setup/progress")
    public DependencySetupProgress progress(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return dependencyService.progress(principal.userId(), projectId);
    }

    /** Cancels a running setup (downloads already finished stay in the project). */
    @DeleteMapping("/setup")
    public DependencySetupProgress cancel(@PathVariable UUID projectId, @AuthenticationPrincipal AuthenticatedUser principal) {
        return dependencyService.cancel(principal.userId(), projectId);
    }
}
