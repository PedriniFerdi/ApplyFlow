package com.applyflow.dto.analytics;

import java.math.BigDecimal;

public record ResponseTimeResponse(
        long sampleSize,
        BigDecimal averageDays,
        BigDecimal medianDays
) {
}
