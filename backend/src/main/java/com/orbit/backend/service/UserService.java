package com.orbit.backend.service;

import com.orbit.backend.dto.request.UpdateUserRequest;
import com.orbit.backend.dto.response.UserResponse;
import com.orbit.backend.entity.User;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse createUser(String userName, String rawPassword) {
        String name = userName.trim();
        if (userRepository.existsByUserName(name)) {
            log.warn("Registration rejected, username '{}' already exists", name);
            throw new ApiException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        User user = new User();
        user.setUserName(name);
        user.setPassword(passwordEncoder.encode(rawPassword));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race with a concurrent registration of the same name.
            throw new ApiException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        log.info("Created user '{}' ({})", user.getUserName(), user.getUserId());
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public long countUsers() {
        return userRepository.count();
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return UserResponse.from(findEntity(userId));
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request, UUID actingUserId) {
        requireSelf(userId, actingUserId);
        User user = findEntity(userId);
        if (request.userName() != null && !request.userName().isBlank()
                && !request.userName().trim().equals(user.getUserName())) {
            String newName = request.userName().trim();
            if (userRepository.existsByUserName(newName)) {
                throw new ApiException(ErrorCode.USERNAME_ALREADY_EXISTS);
            }
            user.setUserName(newName);
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        User saved = userRepository.saveAndFlush(user);
        log.info("Updated user {}", userId);
        return UserResponse.from(saved);
    }

    /** Refresh tokens are removed by the ON DELETE CASCADE foreign key. */
    @Transactional
    public void deleteUser(UUID userId, UUID actingUserId) {
        requireSelf(userId, actingUserId);
        userRepository.delete(findEntity(userId));
        log.info("Deleted user {}", userId);
    }

    private User findEntity(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_RESOURCE_NOT_FOUND));
    }

    private void requireSelf(UUID targetId, UUID actingUserId) {
        if (!targetId.equals(actingUserId)) {
            log.warn("User {} tried to modify user {}", actingUserId, targetId);
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
    }
}
