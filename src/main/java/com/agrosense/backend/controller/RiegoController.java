package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.RiegoRequest;
import com.agrosense.backend.dto.response.IrrigationResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.RiegoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/riego")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class RiegoController {

    private final RiegoService riegoService;

    @GetMapping("/cultivo/{cropId}")
    public List<IrrigationResponse> history(@PathVariable Integer cropId,
            @RequestParam(defaultValue = "50") int limit, Authentication authentication) {
        return riegoService.findHistory(authentication.getName(), cropId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MANAGER)
    public IrrigationResponse register(@Valid @RequestBody RiegoRequest request, Authentication authentication) {
        return riegoService.register(authentication.getName(), request);
    }
}
