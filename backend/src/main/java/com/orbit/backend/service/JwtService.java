package com.orbit.backend.service;

import com.orbit.backend.config.OrbitProperties;
import com.orbit.backend.entity.AuthenticatedUser;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/** Creates and validates short-lived (default 15 min) HS256 access tokens signed with the user's own secret. */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    static final String CLAIM_USER_ID = "user_id";
    static final String CLAIM_USERNAME = "username";

    private final SecretKeyService secretKeyService;
    private final OrbitProperties properties;

    public record AccessToken(String token, Instant expiresAt) {
    }

    public AccessToken generateAccessToken(UUID userId, String username) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(properties.jwt().accessTokenMinutes(), ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))   // the "expire date" claim (exp)
                .signWith(signingKey())
                .compact();
        log.debug("Issued access token for user {} expiring at {}", userId, expiresAt);
        return new AccessToken(token, expiresAt);
    }

    /** @throws ApiException TOKEN_EXPIRED / TOKEN_INVALID / SECRET_KEY_NOT_CONFIGURED */
    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
            String id = claims.get(CLAIM_USER_ID, String.class);
            String username = claims.get(CLAIM_USERNAME, String.class);
            if (id == null || username == null) {
                throw new ApiException(ErrorCode.TOKEN_INVALID);
            }
            return new AuthenticatedUser(UUID.fromString(id), username);
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Rejected invalid access token: {}", e.getMessage());
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secretKeyService.requireSecret().getBytes(StandardCharsets.UTF_8));
    }
}
