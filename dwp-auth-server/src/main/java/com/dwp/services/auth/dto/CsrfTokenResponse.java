package com.dwp.services.auth.dto;

public record CsrfTokenResponse(String token, String headerName) {
}
