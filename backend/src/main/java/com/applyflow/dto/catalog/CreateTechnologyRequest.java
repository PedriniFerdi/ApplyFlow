package com.applyflow.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTechnologyRequest(@NotBlank @Size(max = 100) String name) {
}
