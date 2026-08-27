package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production fail-closed boundary for customer-owned approval evidence.
 *
 * <p>The local profile may exercise the maker/checker flow with a reference
 * fixture. Every non-local environment remains disabled until a trusted
 * customer approval system can verify a signed, tenant/scope/duration/request
 * binding. A ticket-shaped string is never treated as that verification.</p>
 */
@Component
public class CustomerApprovalEvidencePolicy {

    public static final String LOCAL_REFERENCE_ONLY = "LOCAL_REFERENCE_ONLY";

    private final String environment;
    private final boolean localFixturesEnabled;

    public CustomerApprovalEvidencePolicy(
            @Value("${dwp.environment:}") String environment,
            @Value("${dwp.provider.local-approval-fixtures-enabled:false}")
            boolean localFixturesEnabled) {
        this.environment = environment == null ? "" : environment.trim();
        this.localFixturesEnabled = localFixturesEnabled;
    }

    public String requireVerified(String approvalReference) {
        if (approvalReference == null || approvalReference.isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A customer approval reference is required for this support scope.");
        }
        if (!"local".equalsIgnoreCase(environment) || !localFixturesEnabled) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Customer-approved support access is disabled until authoritative, signed "
                            + "approval evidence is bound to the tenant, scopes, duration, requester, "
                            + "request fingerprint, and expiry.");
        }
        return LOCAL_REFERENCE_ONLY;
    }
}
