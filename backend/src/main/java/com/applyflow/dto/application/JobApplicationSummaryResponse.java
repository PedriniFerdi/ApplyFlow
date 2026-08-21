package com.applyflow.dto.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.applyflow.dto.catalog.CatalogItemResponse;
import com.applyflow.dto.catalog.CompanyResponse;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.WorkMode;

public record JobApplicationSummaryResponse(
        Long id,
        CompanyResponse company,
        String positionTitle,
        ApplicationStatus status,
        LocalDate appliedDate,
        CatalogItemResponse source,
        WorkMode workMode,
        List<CatalogItemResponse> technologies,
        Instant createdAt,
        Instant updatedAt
) {
}
