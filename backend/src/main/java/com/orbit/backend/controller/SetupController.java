package com.orbit.backend.controller;

import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.dto.request.EnvironmentRequest;
import com.orbit.backend.dto.response.SetupStatus;
import com.orbit.backend.service.DatabaseProvisioner;
import com.orbit.backend.service.SecretKeyService;
import com.orbit.backend.service.SettingsService;
import com.orbit.backend.service.SetupService;
import com.orbit.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-run wizard support: tells the frontend what still needs to be set up. These endpoints work before the
 * database exists.
 */
@Slf4j
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SecretKeyService secretKeyService;
    private final DatabaseProvisioner databaseProvisioner;
    private final UserService userService;
    private final SettingsService settingsService;
    private final SetupService setupService;

    @GetMapping("/status")
    public SetupStatus status() {
        boolean ready = databaseProvisioner.isReady();
        String theme = ready ? settingsService.getTheme().orElse(null) : null;
        return new SetupStatus(
                secretKeyService.isConfigured(),
                databaseProvisioner.hasCredentials(),
                databaseProvisioner.getState(),
                databaseProvisioner.getMessage(),
                databaseProvisioner.getDatabaseName(),
                databaseProvisioner.getPort(),
                ready && userService.countUsers() > 0,
                theme != null,
                theme);
    }

    @PostMapping("/environment")
    @ResponseStatus(HttpStatus.CREATED)
    public SetupStatus saveEnvironment(@RequestBody EnvironmentRequest request) {
        setupService.configureEnvironment(request.secretKey(), request.dbUsername(), request.dbPassword());
        return status();
    }

    @PostMapping("/database/retry")
    public SetupStatus retryDatabase() {
        databaseProvisioner.retry();
        return status();
    }
}
