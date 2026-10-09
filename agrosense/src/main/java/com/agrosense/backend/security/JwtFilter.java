package com.agrosense.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Authenticates a request from its {@code Authorization: Bearer} header, when it carries a valid token. */
@Slf4j
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final TokenAuthenticator tokenAuthenticator;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            tokenAuthenticator.authenticate(authorizationHeader).ifPresentOrElse(
                    authentication -> SecurityContextHolder.getContext().setAuthentication(authentication),
                    () -> log.debug("Credenciales rechazadas en {} {}",
                            request.getMethod(), request.getRequestURI()));
        }
        chain.doFilter(request, response);
    }
}
