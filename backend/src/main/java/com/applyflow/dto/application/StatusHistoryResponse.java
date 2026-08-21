package com.applyflow.dto.application;

import java.time.Instant;

import com.applyflow.entity.ApplicationStatus;

public record StatusHistoryResponse(Long id, ApplicationStatus status, Instant changedAt) {
}
