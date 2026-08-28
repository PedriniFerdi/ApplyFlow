package com.applyflow.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConfirmDeletionRequest(
        @NotBlank @Size(max = 43) @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Size(max = 6) @Pattern(regexp = "DELETE") String confirmation
) {
}
