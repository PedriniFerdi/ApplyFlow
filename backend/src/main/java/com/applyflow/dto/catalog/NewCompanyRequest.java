package com.applyflow.dto.catalog;

import com.applyflow.entity.CompanyType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.*;

public record NewCompanyRequest(
        @NotBlank @CodePointLength(max = COMPANY_NAME) String name,
        @CodePointLength(max = WEBSITE) String website,
        @NotNull CompanyType companyType,
        @CodePointLength(max = INDUSTRY) String industry
) {
}
