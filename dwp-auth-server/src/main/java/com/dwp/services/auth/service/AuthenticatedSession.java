package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.LoginResponse;

public record AuthenticatedSession(String accessToken, LoginResponse response) {
}
