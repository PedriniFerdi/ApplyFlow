package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.*;

public record ResetPasswordRequest(
        @NotBlank @Size(max = TOKEN) String token,
        @NotBlank @CodePointLength(max = PASSWORD) String password,
        @NotBlank @CodePointLength(max = PASSWORD) String passwordConfirmation
) {
}
