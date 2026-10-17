package com.agrosense.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public Optional<Authentication> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return jwtUtil.extractEmail(authorizationHeader.substring(BEARER_PREFIX.length()).trim())
                .flatMap(this::loadEnabledUser)
                .map(user -> UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    public Optional<Instant> expiration(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return jwtUtil.extractExpiration(authorizationHeader.substring(BEARER_PREFIX.length()).trim());
    }

    public boolean isActive(String email) {
        return loadEnabledUser(email).isPresent();
    }

    private Optional<UserDetails> loadEnabledUser(String email) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(email);
            return user.isEnabled() ? Optional.of(user) : Optional.empty();
        } catch (UsernameNotFoundException exception) {
            return Optional.empty();
        }
    }
}
