package com.applyflow.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.applyflow.dto.analytics.AnalyticsPeriod;
import com.applyflow.dto.analytics.AnalyticsSummaryResponse;
import com.applyflow.dto.analytics.ApplicationsOverTimeResponse;
import com.applyflow.dto.analytics.DimensionAnalyticsResponse;
import com.applyflow.dto.analytics.FunnelResponse;
import com.applyflow.dto.analytics.ResponseTimeResponse;
import com.applyflow.service.AnalyticsService;
import com.applyflow.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public AnalyticsSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.summary(principal.userId());
    }

    @GetMapping("/funnel")
    public FunnelResponse funnel(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.funnel(principal.userId());
    }

    @GetMapping("/applications-over-time")
    public ApplicationsOverTimeResponse applicationsOverTime(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam AnalyticsPeriod period
    ) {
        return analyticsService.applicationsOverTime(principal.userId(), period);
    }

    @GetMapping("/sources")
    public DimensionAnalyticsResponse sources(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.sources(principal.userId());
    }

    @GetMapping("/technologies")
    public DimensionAnalyticsResponse technologies(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.technologies(principal.userId());
    }

    @GetMapping("/response-time")
    public ResponseTimeResponse responseTime(@AuthenticationPrincipal AuthenticatedUser principal) {
        return analyticsService.responseTime(principal.userId());
    }
}
