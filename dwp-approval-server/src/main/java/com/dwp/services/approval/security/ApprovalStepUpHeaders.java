package com.dwp.services.approval.security;

/** Untrusted wire values; they become authority only after signed challenge verification. */
public record ApprovalStepUpHeaders(
        String challenge,
        String idempotencyKey,
        String decisionRevision,
        Long expectedObjectVersion) {

    public static ApprovalStepUpHeaders of(
            String challenge,
            String idempotencyKey,
            String decisionRevision,
            Long expectedObjectVersion) {
        return new ApprovalStepUpHeaders(
                normalized(challenge), normalized(idempotencyKey),
                normalized(decisionRevision), expectedObjectVersion);
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim();
    }
}
