package com.orbit.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly sweep of expired refresh tokens. Does nothing until the database exists and is ready. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final DatabaseProvisioner databaseProvisioner;
    private final RefreshTokenService refreshTokenService;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void sweep() {
        if (!databaseProvisioner.isReady()) {
            log.debug("Skipping expired refresh token sweep: database not ready ({})", databaseProvisioner.getState());
            return;
        }
        try {
            refreshTokenService.deleteExpired();
        } catch (RuntimeException e) {
            log.warn("Expired refresh token sweep failed: {}", e.getMessage());
        }
    }
}
