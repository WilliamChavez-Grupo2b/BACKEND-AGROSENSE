package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.response.LoginResponse;
import com.agrosense.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    /** Lets the sign-up form tell whether an e-mail address is still free, before it is submitted. */
    @GetMapping("/email-available")
    public Map<String, Boolean> emailAvailable(@RequestParam String email) {
        return Map.of("available", authService.isEmailAvailable(email));
    }
}
