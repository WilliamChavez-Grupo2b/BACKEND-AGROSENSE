package com.agrosense.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a bearer token into an authentication. The user is re-read from the database on every use, so a
 * deleted or disabled account stops working at once and roles always reflect the current data, not the
 * token's.
 */
@Component
@RequiredArgsConstructor
public class TokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    /** @param authorizationHeader the raw header value, for example {@code "Bearer eyJ..."} */
    public Optional<Authentication> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return jwtService.extractEmail(authorizationHeader.substring(BEARER_PREFIX.length()).trim())
                .flatMap(this::loadEnabledUser)
                .map(user -> UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
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
