package com.applyflow.dto.catalog;

import com.applyflow.entity.CompanyType;

public record CompanyResponse(
        Long id,
        String name,
        String website,
        CompanyType companyType,
        String industry
) {
}
