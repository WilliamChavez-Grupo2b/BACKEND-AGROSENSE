package com.agrosense.backend.controller;

import com.agrosense.backend.dto.response.DataSourcesResponse;
import com.agrosense.backend.security.Roles;
import com.agrosense.backend.service.DataSourceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/data-sources")
@RequiredArgsConstructor
@PreAuthorize(Roles.ANY)
public class DataSourceController {

    private final DataSourceCatalogService catalog;

    @GetMapping
    public DataSourcesResponse dataSources(Principal principal) {
        return catalog.describe(principal.getName());
    }
}
