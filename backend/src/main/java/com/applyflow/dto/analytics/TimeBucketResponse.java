package com.applyflow.dto.analytics;

import java.time.LocalDate;

public record TimeBucketResponse(LocalDate startDate, long applicationCount) {
}
