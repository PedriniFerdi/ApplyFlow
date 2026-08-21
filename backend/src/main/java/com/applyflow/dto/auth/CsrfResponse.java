package com.applyflow.dto.auth;

public record CsrfResponse(String token, String headerName) {
}
