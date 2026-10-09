package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.LoginRequest;
import com.agrosense.backend.dto.response.LoginResponse;
import com.agrosense.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
