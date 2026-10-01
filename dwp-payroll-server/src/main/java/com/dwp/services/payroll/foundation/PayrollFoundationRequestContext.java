package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;

final class PayrollFoundationRequestContext {

    private static final ThreadLocal<VerifiedSubject> CURRENT = new ThreadLocal<>();

    private PayrollFoundationRequestContext() {
    }

    static void set(VerifiedSubject subject) {
        CURRENT.set(subject);
    }

    static VerifiedSubject require() {
        VerifiedSubject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(
                    ErrorCode.UNAUTHORIZED,
                    "Verified payroll request context is required.");
        }
        return subject;
    }

    static void clear() {
        CURRENT.remove();
    }

    record VerifiedSubject(
            long tenantId,
            long actorId,
            FoundationAction action,
            String purpose,
            String contextKey,
            String contextScopeKey,
            String policyRevision,
            String authorizationRevision,
            Instant revalidateAt,
            String routeContractKey) {

        VerifiedSubject {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException("tenantId and actorId must be positive");
            }
            if (action == null || revalidateAt == null) {
                throw new IllegalArgumentException("action and revalidateAt are required");
            }
        }
    }
}
