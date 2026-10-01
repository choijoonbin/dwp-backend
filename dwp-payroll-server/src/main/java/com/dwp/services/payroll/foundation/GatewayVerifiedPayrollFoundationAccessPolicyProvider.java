package com.dwp.services.payroll.foundation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Production PAY policy projection for requests already admitted by the exact Gateway route PEP.
 *
 * <p>The provider deliberately contains no role-name mapping. The request filter projects one
 * exact, Gateway-authorized route into one local action; tenant, subject, scope and freshness are
 * independently checked before this policy is consulted.</p>
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class GatewayVerifiedPayrollFoundationAccessPolicyProvider
        implements PayrollFoundationAccessPolicyProvider {

    private static final PayrollFoundationAccess.AccessPolicy POLICY =
            new PayrollFoundationAccess.AccessPolicy(
                    "PAYROLL-GATEWAY-OWNER-V1",
                    1,
                    "PAYROLL_FOUNDATION",
                    Set.of("APP.HCM:VIEW"),
                    Set.of("PAYROLL_CONFIGURATION"),
                    Set.of("PAYROLL_AUDIT"),
                    Map.of(),
                    Map.of());

    @Override
    public PayrollFoundationAccess.AccessPolicy policyFor(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        return POLICY;
    }
}
