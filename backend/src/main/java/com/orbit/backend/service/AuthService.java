package com.orbit.backend.service;

import com.orbit.backend.dto.request.LoginRequest;
import com.orbit.backend.dto.request.RegisterRequest;
import com.orbit.backend.dto.response.TokenResponse;
import com.orbit.backend.dto.response.UserResponse;
import com.orbit.backend.entity.OrbitUserPrincipal;
import com.orbit.backend.entity.RefreshToken;
import com.orbit.backend.entity.User;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final SecretKeyService secretKeyService;

    public UserResponse register(RegisterRequest request) {
        return userService.createUser(request.username(), request.password());
    }

    public TokenResponse login(LoginRequest request) {
        secretKeyService.requireSecret();
        String username = request.username().trim();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (UsernameNotFoundException e) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        } catch (BadCredentialsException e) {
            log.warn("Wrong password for user '{}'", username);
            throw new ApiException(ErrorCode.INVALID_PASSWORD);
        }
        OrbitUserPrincipal principal = (OrbitUserPrincipal) authentication.getPrincipal();
        JwtService.AccessToken access = jwtService.generateAccessToken(principal.userId(), principal.username());
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.create(principal.userId());
        log.info("User '{}' logged in", principal.username());
        return new TokenResponse("Bearer", access.token(), access.expiresAt(),
                refresh.rawToken(), refresh.expiresAt(), userService.getUser(principal.userId()));
    }

    /** Exchanges a valid refresh token for a new short-lived access token. */
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenService.validate(rawRefreshToken);
        UserResponse user;
        try {
            user = userService.getUser(stored.getUserId());
        } catch (ApiException e) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        JwtService.AccessToken access = jwtService.generateAccessToken(user.userId(), user.userName());
        log.info("Access token refreshed for user '{}'", user.userName());
        return new TokenResponse("Bearer", access.token(), access.expiresAt(),
                rawRefreshToken, stored.getExpiredAt(), null);
    }

    public void logout(UUID userId, String rawRefreshToken) {
        refreshTokenService.deleteByToken(rawRefreshToken, userId);
    }
}
