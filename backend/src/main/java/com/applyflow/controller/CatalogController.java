package com.applyflow.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.applyflow.dto.catalog.CatalogItemResponse;
import com.applyflow.dto.catalog.CompanyResponse;
import com.applyflow.dto.catalog.CreateTechnologyRequest;
import com.applyflow.service.CatalogService;
import com.applyflow.security.AuthenticatedUser;

import jakarta.validation.Valid;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.COMPANY_NAME;

@RestController
@Validated
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/api/companies")
    public List<CompanyResponse> companies(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @CodePointLength(max = COMPANY_NAME) String query
    ) {
        return catalogService.findCompanies(principal.userId(), query);
    }

    @GetMapping("/api/technologies")
    public List<CatalogItemResponse> technologies(@AuthenticationPrincipal AuthenticatedUser principal) {
        return catalogService.findTechnologies(principal.userId());
    }

    @PostMapping("/api/technologies")
    public ResponseEntity<CatalogItemResponse> createTechnology(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateTechnologyRequest request
    ) {
        CatalogItemResponse created = catalogService.createTechnology(principal.userId(), request);
        return ResponseEntity.created(URI.create("/api/technologies/" + created.id())).body(created);
    }

    @GetMapping("/api/sources")
    public List<CatalogItemResponse> sources(@AuthenticationPrincipal AuthenticatedUser principal) {
        return catalogService.findSources();
    }
}
