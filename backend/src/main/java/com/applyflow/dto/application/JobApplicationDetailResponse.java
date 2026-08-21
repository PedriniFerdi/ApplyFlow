package com.applyflow.dto.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.applyflow.dto.catalog.CatalogItemResponse;
import com.applyflow.dto.catalog.CompanyResponse;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;

public record JobApplicationDetailResponse(
        Long id,
        CompanyResponse company,
        String positionTitle,
        String jobUrl,
        LocalDate appliedDate,
        ApplicationStatus status,
        CatalogItemResponse source,
        WorkMode workMode,
        String location,
        String salaryMin,
        String salaryMax,
        String currency,
        SalaryPeriod salaryPeriod,
        String notes,
        List<CatalogItemResponse> technologies,
        Instant createdAt,
        Instant updatedAt,
        List<StatusHistoryResponse> history
) {
}
