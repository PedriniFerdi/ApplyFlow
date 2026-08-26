package com.applyflow.dto.application;

import java.time.LocalDate;
import java.util.List;

import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.*;

public record CreateJobApplicationRequest(
        @Positive Long companyId,
        @Valid NewCompanyRequest newCompany,
        @NotBlank @CodePointLength(max = TITLE) String positionTitle,
        @CodePointLength(max = URL) String jobUrl,
        LocalDate appliedDate,
        @NotNull ApplicationStatus status,
        @NotNull @Positive Long sourceId,
        @NotNull WorkMode workMode,
        @CodePointLength(max = LOCATION) String location,
        @Size(max = SALARY) @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String salaryMin,
        @Size(max = SALARY) @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String salaryMax,
        @Size(max = CURRENCY) String currency,
        SalaryPeriod salaryPeriod,
        @CodePointLength(max = NOTES) String notes,
        @NotNull @Size(max = TECHNOLOGIES) List<@Positive Long> technologyIds
) {
}
