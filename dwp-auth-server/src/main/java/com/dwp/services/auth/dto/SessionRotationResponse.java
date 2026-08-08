package com.dwp.services.auth.dto;

import java.time.Instant;

public record SessionRotationResponse(
        boolean rotated,
        Instant idleExpiresAt,
        Instant expiresAt) {
}
