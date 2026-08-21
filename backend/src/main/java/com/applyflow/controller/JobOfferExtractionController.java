package com.applyflow.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.applyflow.dto.application.ExtractJobOfferRequest;
import com.applyflow.dto.application.JobOfferExtractionResponse;
import com.applyflow.security.AuthenticatedUser;
import com.applyflow.service.AuthenticationRateLimiter;
import com.applyflow.service.JobOfferExtractionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/job-offers")
public class JobOfferExtractionController {

    private final JobOfferExtractionService extractionService;
    private final AuthenticationRateLimiter rateLimiter;

    public JobOfferExtractionController(
            JobOfferExtractionService extractionService,
            AuthenticationRateLimiter rateLimiter
    ) {
        this.extractionService = extractionService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/extract")
    public JobOfferExtractionResponse extract(
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest servletRequest,
            @Valid @RequestBody ExtractJobOfferRequest request
    ) {
        rateLimiter.checkJobOfferExtraction(servletRequest.getRemoteAddr(), principal.userId());
        return extractionService.extract(principal.userId(), request.url());
    }
}
