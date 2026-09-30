package com.orbit.backend.setup;

import com.orbit.backend.common.exception.ApiException;
import com.orbit.backend.common.exception.ErrorCode;
import com.orbit.backend.config.OrbitProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the JWT signing secret. The secret is chosen by the user in the first-run setup screen and persisted as
 * {@code JWT_SECRET} in the project's {@code .env} file (see {@link SetupService}), so every machine has its own key.
 * On later starts it is loaded from the environment / .env (see {@code spring.config.import} in application.yaml).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretKeyService {

    private final OrbitProperties properties;
    private final AtomicReference<String> secret = new AtomicReference<>();

    @PostConstruct
    void load() {
        String configured = properties.jwt().secret();
        if (configured != null && !configured.isBlank()) {
            secret.set(configured.trim());
            log.info("JWT secret key loaded from environment / .env");
        } else {
            log.warn("No JWT secret key configured yet - waiting for first-run setup");
        }
    }

    public boolean isConfigured() {
        return secret.get() != null;
    }

    public String requireSecret() {
        String value = secret.get();
        if (value == null) {
            throw new ApiException(ErrorCode.SECRET_KEY_NOT_CONFIGURED);
        }
        return value;
    }

    /** Makes an already validated and persisted secret active. */
    void activate(String validatedSecret) {
        secret.set(validatedSecret);
    }
}
