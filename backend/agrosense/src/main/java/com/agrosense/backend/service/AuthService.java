package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.response.LoginResponse;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.UserRepository;
import com.agrosense.backend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    /**
     * Checks the credentials and issues a token.
     *
     * @throws org.springframework.security.core.AuthenticationException when they are wrong or the
     *         account is disabled; the caller answers both cases the same way
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.getPassword()));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("User disappeared during login"));
        user.setLastAccessAt(LocalDateTime.now());
        return new LoginResponse(
                jwtUtil.generateToken(user.getEmail(), user.getRole()),
                user.getName(),
                user.getRole(),
                user.getIdUser());
    }
}
