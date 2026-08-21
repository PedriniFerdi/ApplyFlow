package com.applyflow.dto.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExtractJobOfferRequest(
        @NotBlank @Size(max = 1000) String url
) {
}
