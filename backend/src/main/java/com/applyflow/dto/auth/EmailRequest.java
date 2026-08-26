package com.applyflow.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import static com.applyflow.validation.RequestLimits.EMAIL;

public record EmailRequest(@NotBlank @Email @CodePointLength(max = EMAIL) String email) {
}
