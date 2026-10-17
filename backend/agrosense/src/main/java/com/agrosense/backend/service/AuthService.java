package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.request.RegisterRequest;
import com.agrosense.backend.dto.response.LoginResponse;
import com.agrosense.backend.dto.response.RegisterResponse;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.TooManyLoginAttemptsException;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.UserRepository;
import com.agrosense.backend.security.JwtUtil;
import com.agrosense.backend.security.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String DEFAULT_ROLE = "farmer";

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttempts;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public LoginResponse login(LoginRequest request, String clientAddress) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        loginAttempts.remainingLock(email, clientAddress).ifPresent(remaining -> {
            log.warn("Sign-in blocked after too many failed attempts from {}", clientAddress);
            throw new TooManyLoginAttemptsException(remaining);
        });
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.getPassword()));
        } catch (AuthenticationException exception) {
            loginAttempts.recordFailure(email, clientAddress);
            throw exception;
        }
        loginAttempts.recordSuccess(email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("User disappeared during login"));
        user.setLastAccessAt(LocalDateTime.now());
        return new LoginResponse(
                jwtUtil.generateToken(user.getEmail(), user.getRole()),
                user.getName(),
                user.getRole(),
                user.getIdUser());
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessRuleException("Ya existe una cuenta con ese correo.");
        }
        User user = userRepository.saveAndFlush(User.builder()
                .name(request.getName().trim())
                .lastName(request.getLastName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(DEFAULT_ROLE)
                .build());
        log.info("Account created with id {}", user.getIdUser());
        return new RegisterResponse(user.getIdUser(), user.getName(), user.getLastName(), user.getEmail(),
                user.getRole());
    }

    @Transactional(readOnly = true)
    public boolean userExists(String email) {
        return userRepository.existsByEmail(email.trim().toLowerCase(Locale.ROOT));
    }
}
