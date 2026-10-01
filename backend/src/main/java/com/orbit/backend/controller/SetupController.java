package com.orbit.backend.controller;

import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.dto.request.EnvironmentRequest;
import com.orbit.backend.dto.response.SetupStatus;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.service.DatabaseProvisioner;
import com.orbit.backend.service.FolderSettingsService;
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

import java.util.List;

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
    private final FolderSettingsService folderSettingsService;

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
                theme,
                folderSettingsService.setupNeeded(),
                folderSettingsService.restartRequired(),
                folderSettingsService.desired());
    }

    @PostMapping("/environment")
    @ResponseStatus(HttpStatus.CREATED)
    public SetupStatus saveEnvironment(@RequestBody EnvironmentRequest request) {
        List<String> folders = request.folders();
        boolean envFieldsGiven = notBlank(request.secretKey()) || notBlank(request.dbUsername()) || notBlank(request.dbPassword());
        boolean envMissing = !secretKeyService.isConfigured() || !databaseProvisioner.hasCredentials();

        if (folders != null && !folderSettingsService.setupNeeded()) {
            throw new ApiException(ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED, "The folders are already configured");
        }
        List<String> validFolders = folders == null ? null : folderSettingsService.validate(folders); // before anything is written

        // Only touch the secret/database part when it is needed (or when the old API is used without folders).
        if (folders == null || envMissing || envFieldsGiven) {
            setupService.configureEnvironment(request.secretKey(), request.dbUsername(), request.dbPassword());
        }
        if (validFolders != null) {
            folderSettingsService.save(validFolders);
            if (folderSettingsService.restartRequired()) {
                folderSettingsService.requestApply(); // no-op when the host helper is not running
            }
        }
        return status();
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    @PostMapping("/database/retry")
    public SetupStatus retryDatabase() {
        databaseProvisioner.retry();
        return status();
    }
}
