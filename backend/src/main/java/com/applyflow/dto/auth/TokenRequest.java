package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static com.applyflow.validation.RequestLimits.TOKEN;

public record TokenRequest(@NotBlank @Size(max = TOKEN) String token) {
}
