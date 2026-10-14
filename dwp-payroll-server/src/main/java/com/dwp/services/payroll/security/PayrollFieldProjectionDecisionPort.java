package com.dwp.services.payroll.security;

/**
 * Owner-side boundary for trusted field-policy decisions. The current Gateway
 * bridge supplies only a revision, so production deliberately has no permissive
 * fallback for the closed PAY-011 response schemas.
 */
public interface PayrollFieldProjectionDecisionPort {

    enum Decision { FULL, MASK, OMIT, UNAVAILABLE }

    Decision decision(
            PayrollCalculationAuthority.Subject subject,
            String responseFieldGroup);
}
