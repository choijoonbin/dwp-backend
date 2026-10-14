package com.dwp.services.payroll.security;

import java.time.Instant;

public record PayrollStepUpEvidence(
        String challengeId,
        String nonce,
        String issuer,
        Instant expiresAt,
        String payloadSha256,
        String decisionRevision) {
}
