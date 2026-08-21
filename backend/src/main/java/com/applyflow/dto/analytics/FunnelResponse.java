package com.applyflow.dto.analytics;

import java.util.List;

public record FunnelResponse(List<FunnelStageResponse> stages) {
}
