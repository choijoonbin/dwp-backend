package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Set;

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
            Set<String> roles,
            Set<String> permissions,
            String purpose,
            String legalEntityScope,
            String policyRevision,
            String authorizationRevision) {

        VerifiedSubject {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }
}
