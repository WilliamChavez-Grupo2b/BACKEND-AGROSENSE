package com.agrosense.backend.controller;

import com.agrosense.backend.dto.request.CultivoRequest;
import com.agrosense.backend.dto.request.FincaRequest;
import com.agrosense.backend.dto.response.CropResponse;
import com.agrosense.backend.dto.response.EstateResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.FincaCultivoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class FincaCultivoController {

    private final FincaCultivoService fincaCultivoService;

    @GetMapping("/fincas")
    public List<EstateResponse> estates(Authentication authentication) {
        return fincaCultivoService.findEstates(authentication.getName());
    }

    @PostMapping("/fincas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MANAGER)
    public EstateResponse createEstate(@Valid @RequestBody FincaRequest request, Authentication authentication) {
        return fincaCultivoService.createEstate(authentication.getName(), request);
    }

    /** Active crops of one estate, or of all the user's estates when {@code estateId} is left out. */
    @GetMapping("/cultivos")
    public List<CropResponse> crops(@RequestParam(required = false) Integer estateId,
            Authentication authentication) {
        return fincaCultivoService.findActiveCrops(authentication.getName(), estateId);
    }

    @PostMapping("/cultivos")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MANAGER)
    public CropResponse createCrop(@Valid @RequestBody CultivoRequest request, Authentication authentication) {
        return fincaCultivoService.createCrop(authentication.getName(), request);
    }
}
