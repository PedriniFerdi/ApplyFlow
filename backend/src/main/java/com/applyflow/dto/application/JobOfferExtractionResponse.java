package com.applyflow.dto.application;

import java.util.List;
import java.util.Map;

import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;

public record JobOfferExtractionResponse(
        String canonicalUrl,
        String positionTitle,
        CompanySuggestion company,
        Long sourceId,
        WorkMode workMode,
        String location,
        SalarySuggestion salary,
        List<String> warnings,
        Map<String, Confidence> confidence
) {
    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public record CompanySuggestion(
            String name,
            String website,
            Long existingCompanyId
    ) {
    }

    public record SalarySuggestion(
            String min,
            String max,
            String currency,
            SalaryPeriod period
    ) {
    }
}
