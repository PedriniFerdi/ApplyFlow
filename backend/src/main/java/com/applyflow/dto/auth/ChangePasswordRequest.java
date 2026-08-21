package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String password,
        @NotBlank String passwordConfirmation
) {
}
