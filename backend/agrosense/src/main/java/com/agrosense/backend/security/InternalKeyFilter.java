package com.agrosense.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Lets into {@code /api/internal/**} only the callers that send {@code X-Internal-Key} with the configured
 * key ({@code EVENTS_INTERNAL_KEY}), before the body is read. Without a configured key nobody gets in.
 */
@Slf4j
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String PATH = "/api/internal/";
    public static final String HEADER = "X-Internal-Key";

    private final byte[] key;

    public InternalKeyFilter(String key) {
        this.key = key == null ? new byte[0] : key.trim().getBytes(StandardCharsets.UTF_8);
        if (this.key.length == 0) {
            log.info("EVENTS_INTERNAL_KEY sin valor: las rutas internas de eventos rechazan toda llamada");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        // Compared in constant time, so the key cannot be found out byte by byte.
        if (key.length > 0 && sent != null
                && MessageDigest.isEqual(key, sent.trim().getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Llamada interna rechazada: {} {} sin la llave interna válida", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"Falta la llave interna o no es válida.\"}");
    }
}
