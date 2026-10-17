package com.agrosense.backend.config;

import com.agrosense.backend.security.InternalKeyFilter;
import com.agrosense.backend.security.JwtFilter;
import com.agrosense.backend.security.TokenAuthenticator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticator tokenAuthenticator,
            @Value("${events.internal-key:}") String eventsInternalKey) throws Exception {
        http
                // The API is stateless and authenticates with a header, never a cookie, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Also with HEAD, which is what uptime monitors and some hosts send.
                        .requestMatchers(HttpMethod.GET, "/", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/check-user", "/api/auth/email-available")
                        .permitAll()
                        // Browsers cannot send headers on the WebSocket handshake; the token is checked on
                        // the STOMP CONNECT frame instead (see StompAuthChannelInterceptor).
                        .requestMatchers("/ws/**").permitAll()
                        // Called by the event service, not by users: InternalKeyFilter lets in only the
                        // requests with X-Internal-Key, and each event is checked against its device's key.
                        .requestMatchers(HttpMethod.POST, "/api/internal/events").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorizedEntryPoint()))
                .addFilterBefore(new InternalKeyFilter(eventsInternalKey), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtFilter(tokenAuthenticator),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Used by the login endpoint; built from the UserDetailsService and PasswordEncoder beans. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    private static AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"Debes iniciar sesión para acceder a este recurso.\"}");
        };
    }
}
