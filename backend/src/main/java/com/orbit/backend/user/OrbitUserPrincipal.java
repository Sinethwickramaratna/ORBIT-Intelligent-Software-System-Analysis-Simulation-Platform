package com.orbit.backend.user;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** {@link UserDetails} adapter over the {@link User} entity, used during credential authentication. */
public record OrbitUserPrincipal(UUID userId, String username, String password) implements UserDetails {

    public static OrbitUserPrincipal from(User user) {
        return new OrbitUserPrincipal(user.getUserId(), user.getUserName(), user.getPassword());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
