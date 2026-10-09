package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.response.LoginResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttempts;

    /**
     * Checks the credentials and issues a token.
     *
     * @param clientAddress network address the request came from, used to limit failed attempts
     * @throws org.springframework.security.core.AuthenticationException when they are wrong or the
     *         account is disabled; the caller answers both cases the same way
     * @throws TooManyLoginAttemptsException while the account or the address is locked after too many
     *         failures; the password is not even checked then
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String clientAddress) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        loginAttempts.remainingLock(email, clientAddress).ifPresent(remaining -> {
            log.warn("Inicio de sesión bloqueado por demasiados intentos fallidos desde {}", clientAddress);
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

    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return !userRepository.existsByEmail(email.trim().toLowerCase(Locale.ROOT));
    }
}
