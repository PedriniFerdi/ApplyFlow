package com.applyflow.dto.analytics;

import com.applyflow.entity.ApplicationStatus;

public record FunnelStageResponse(ApplicationStatus status, long count) {
}
