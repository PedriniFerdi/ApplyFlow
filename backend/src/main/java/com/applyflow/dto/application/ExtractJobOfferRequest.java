package com.applyflow.dto.application;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.URL;

public record ExtractJobOfferRequest(
        @NotBlank @CodePointLength(max = URL) String url
) {
}
