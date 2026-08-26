package com.applyflow.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.TECHNOLOGY_NAME;

public record CreateTechnologyRequest(@NotBlank @CodePointLength(max = TECHNOLOGY_NAME) String name) {
}
