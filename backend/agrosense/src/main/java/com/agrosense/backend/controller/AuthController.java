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
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping({"/check-user", "/email-available"})
    public UserCheckResponse checkUser(@RequestParam String email) {
        return UserCheckResponse.of(authService.userExists(email));
    }
}
