package com.orbit.backend.filter;

import com.orbit.backend.entity.AuthenticatedUser;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Part of the Spring Security filter chain: reads {@code Authorization: Bearer <access token>}, validates it and
 * populates the SecurityContext. Failures are recorded on the request so {@link JwtAuthenticationEntryPoint} can
 * answer with a precise error (expired vs invalid) when a protected endpoint is hit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String ERROR_ATTRIBUTE = "orbit.auth.error";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            try {
                AuthenticatedUser user = jwtService.parse(header.substring(BEARER.length()).trim());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ApiException ex) {
                log.debug("Access token rejected for {}: {}", request.getRequestURI(), ex.getErrorCode());
                request.setAttribute(ERROR_ATTRIBUTE, ex);
            }
        }
        chain.doFilter(request, response);
    }
}
