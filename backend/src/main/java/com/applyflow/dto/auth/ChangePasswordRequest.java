package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.PASSWORD;

public record ChangePasswordRequest(
        @NotBlank @CodePointLength(max = PASSWORD) String currentPassword,
        @NotBlank @CodePointLength(max = PASSWORD) String password,
        @NotBlank @CodePointLength(max = PASSWORD) String passwordConfirmation
) {
}
