package com.dwp.services.payroll.statutory.pay003;

import java.time.Instant;

/** Immutable high-risk command evidence returned by the PAY-003 step-up port. */
record Pay003StepUpEvidence(
        String challengeId,
        String nonce,
        String issuer,
        Instant expiresAt,
        String payloadSha256,
        String decisionRevision) {
}
