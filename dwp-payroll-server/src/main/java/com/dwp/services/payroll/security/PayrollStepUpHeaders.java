package com.dwp.services.payroll.security;

public record PayrollStepUpHeaders(
        String challenge,
        String idempotencyKey,
        String expectedDecisionRevision,
        long expectedObjectVersion) {
}
