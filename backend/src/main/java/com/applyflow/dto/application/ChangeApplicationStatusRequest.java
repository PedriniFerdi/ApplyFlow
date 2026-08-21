package com.applyflow.dto.application;

import java.time.LocalDate;

import com.applyflow.entity.ApplicationStatus;

import jakarta.validation.constraints.NotNull;

public record ChangeApplicationStatusRequest(
        @NotNull ApplicationStatus status,
        LocalDate appliedDate
) {
}
