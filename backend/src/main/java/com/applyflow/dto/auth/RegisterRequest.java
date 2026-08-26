package com.applyflow.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.*;

public record RegisterRequest(
        @NotBlank @CodePointLength(max = FULL_NAME) String fullName,
        @NotBlank @Email @CodePointLength(max = EMAIL) String email,
        @NotBlank @CodePointLength(max = PASSWORD) String password,
        @NotBlank @CodePointLength(max = PASSWORD) String passwordConfirmation
) {
}
