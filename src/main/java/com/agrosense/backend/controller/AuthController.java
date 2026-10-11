package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.request.RegisterRequest;
import com.agrosense.backend.dto.response.LoginResponse;
import com.agrosense.backend.dto.response.RegisterResponse;
import com.agrosense.backend.dto.response.UserCheckResponse;
import com.agrosense.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // Behind a reverse proxy this is the proxy's address unless server.forward-headers-strategy is set.
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    /** Creates a farmer account. 409 when the e-mail already has one, 400 with the rejected fields otherwise. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /**
     * Lets the sign-up form tell whether an e-mail address is taken, before it is submitted.
     * {@code /email-available} is the older name of the same check and is kept for deployed clients.
     */
    @GetMapping({"/check-user", "/email-available"})
    public UserCheckResponse checkUser(@RequestParam String email) {
        return UserCheckResponse.of(authService.userExists(email));
    }
}
