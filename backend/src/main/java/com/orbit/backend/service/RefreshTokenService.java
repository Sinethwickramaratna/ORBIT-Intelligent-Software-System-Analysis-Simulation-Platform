package com.orbit.backend.service;

import com.orbit.backend.config.OrbitProperties;
import com.orbit.backend.entity.RefreshToken;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens: opaque 256-bit random values that live 24 hours and are stored (hashed) in refresh_token_table.
 * They are deleted on logout and when found expired (plus an hourly sweep).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final OrbitProperties properties;

    public record IssuedRefreshToken(String rawToken, Instant expiresAt) {
    }

    @Transactional
    public IssuedRefreshToken create(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().refreshTokenHours(), ChronoUnit.HOURS);
        repository.save(new RefreshToken(UUID.randomUUID(), hash(raw), userId, now, expiresAt));
        log.debug("Created refresh token for user {} expiring at {}", userId, expiresAt);
        return new IssuedRefreshToken(raw, expiresAt);
    }

    /**
     * Returns the stored token if it exists and is still valid. An expired token is deleted before the
     * exception is thrown, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshToken validate(String rawToken) {
        RefreshToken stored = repository.findByToken(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (!stored.getExpiredAt().isAfter(Instant.now())) {
            repository.delete(stored);
            log.info("Deleted expired refresh token {} of user {}", stored.getTokenId(), stored.getUserId());
            throw new ApiException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        return stored;
    }

    /** Logout: removes the presented token if it belongs to the given user. */
    @Transactional
    public void deleteByToken(String rawToken, UUID userId) {
        int removed = repository.deleteByTokenAndUserId(hash(rawToken), userId);
        log.info("Logout for user {}: {} refresh token(s) deleted", userId, removed);
    }

    @Transactional
    public void deleteByUserId(UUID userId) {
        int removed = repository.deleteAllByUserId(userId);
        log.info("Deleted {} refresh token(s) of user {}", removed, userId);
    }

    @Transactional
    public void deleteExpired() {
        int removed = repository.deleteAllExpiredBefore(Instant.now());
        if (removed > 0) {
            log.info("Expired refresh token sweep removed {} row(s)", removed);
        }
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
