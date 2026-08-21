package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 256) String token,
        @NotBlank String password,
        @NotBlank String passwordConfirmation
) {
}
