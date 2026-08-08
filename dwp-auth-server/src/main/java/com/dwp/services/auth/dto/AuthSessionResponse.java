package com.dwp.services.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record AuthSessionResponse(
        UUID sessionId,
        boolean current,
        String ipAddress,
        String userAgent,
        Instant startedAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant expiresAt) {
}
