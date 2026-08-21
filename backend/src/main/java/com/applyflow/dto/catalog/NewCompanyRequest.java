package com.applyflow.dto.catalog;

import com.applyflow.entity.CompanyType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NewCompanyRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 500) String website,
        @NotNull CompanyType companyType,
        @Size(max = 120) String industry
) {
}
