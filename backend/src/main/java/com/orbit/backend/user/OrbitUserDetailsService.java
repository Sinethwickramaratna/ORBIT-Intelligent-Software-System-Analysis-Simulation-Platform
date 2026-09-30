package com.orbit.backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Step 1 of authentication: does the username exist in the database? */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrbitUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUserName(username)
                .map(OrbitUserPrincipal::from)
                .orElseThrow(() -> {
                    log.warn("Login attempt for unknown username '{}'", username);
                    return new UsernameNotFoundException("No user with username " + username);
                });
    }
}
