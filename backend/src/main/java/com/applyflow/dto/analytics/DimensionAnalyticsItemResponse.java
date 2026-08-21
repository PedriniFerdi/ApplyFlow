package com.applyflow.dto.analytics;

import java.math.BigDecimal;

public record DimensionAnalyticsItemResponse(
        long id,
        String name,
        long applicationCount,
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
