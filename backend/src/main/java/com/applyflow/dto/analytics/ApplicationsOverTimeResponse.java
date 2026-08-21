package com.applyflow.dto.analytics;

import java.util.List;

public record ApplicationsOverTimeResponse(
        AnalyticsPeriod period,
        List<TimeBucketResponse> buckets
) {
}
