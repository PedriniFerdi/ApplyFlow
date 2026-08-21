package com.applyflow.dto.application;

import java.util.List;

public record JobApplicationPageResponse(
        List<JobApplicationSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
