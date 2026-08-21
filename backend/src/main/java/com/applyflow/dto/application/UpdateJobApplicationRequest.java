package com.applyflow.dto.application;

import java.time.LocalDate;
import java.util.List;

import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateJobApplicationRequest(
        @Positive Long companyId,
        @Valid NewCompanyRequest newCompany,
        @NotBlank @Size(max = 180) String positionTitle,
        @Size(max = 1000) String jobUrl,
        LocalDate appliedDate,
        @NotNull @Positive Long sourceId,
        @NotNull WorkMode workMode,
        @Size(max = 160) String location,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String salaryMin,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String salaryMax,
        String currency,
        SalaryPeriod salaryPeriod,
        String notes,
        @NotNull List<@Positive Long> technologyIds
) {
}
