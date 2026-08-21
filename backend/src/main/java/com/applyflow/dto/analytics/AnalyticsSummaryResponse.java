package com.applyflow.dto.analytics;

import java.math.BigDecimal;

public record AnalyticsSummaryResponse(
        long totalApplications,
        long appliedApplications,
        long responseCount,
        BigDecimal responseRate,
        long interviewCount,
        BigDecimal interviewRate,
        long offerCount,
        BigDecimal offerRate,
        long rejectionCount,
        BigDecimal rejectionRate
) {
}
