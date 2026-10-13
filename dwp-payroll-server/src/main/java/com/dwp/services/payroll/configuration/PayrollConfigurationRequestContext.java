package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;

final class PayrollConfigurationRequestContext {

    private static final ThreadLocal<VerifiedSubject> CURRENT = new ThreadLocal<>();

    private PayrollConfigurationRequestContext() {
    }

    static void set(VerifiedSubject subject) {
        CURRENT.set(subject);
    }

    static VerifiedSubject require() {
        VerifiedSubject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(
                    ErrorCode.UNAUTHORIZED,
                    "Verified payroll configuration request context is required.");
        }
        return subject;
    }

    static void clear() {
        CURRENT.remove();
    }

    record VerifiedSubject(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Action routeAction,
            Set<Action> projectedActions,
            String purposeCode,
            String contextScopeKey,
            String fieldPolicyRevision,
            String policyRevision,
            String authorizationRevision,
            Instant revalidateAt,
            String routeContractId) {
        VerifiedSubject {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException("tenant and actor must be positive");
            }
            if (subjectPrincipalPublicId == null || routeAction == null
                    || projectedActions == null || !projectedActions.contains(routeAction)
                    || revalidateAt == null) {
                throw new IllegalArgumentException("verified subject evidence is incomplete");
            }
            projectedActions = Set.copyOf(projectedActions);
        }
    }
}
