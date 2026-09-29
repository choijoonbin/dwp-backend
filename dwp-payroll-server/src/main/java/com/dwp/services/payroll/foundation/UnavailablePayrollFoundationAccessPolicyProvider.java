package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production remains unavailable until the tenant/effective-dated PEP adapter is integrated. */
@Component
@ConditionalOnMissingBean(PayrollFoundationAccessPolicyProvider.class)
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class UnavailablePayrollFoundationAccessPolicyProvider
        implements PayrollFoundationAccessPolicyProvider {

    @Override
    public PayrollFoundationAccess.AccessPolicy policyFor(long tenantId) {
        throw new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Tenant payroll foundation authority evidence is unavailable.");
    }
}
