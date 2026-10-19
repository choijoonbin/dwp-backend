package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Pay003Authority {

    private Pay003Authority() {
    }

    record Subject(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Pay003Route route,
            String purposeCode,
            String contextKey,
            String contextScopeKey,
            long fieldPolicyRevision,
            long authorizationRevision,
            String decisionRevision,
            Instant revalidateAt,
            String accessMode,
            String populationKind,
            String fieldPolicyKey,
            Set<String> permissions) {

        Subject {
            if (tenantId < 1 || actorId < 1 || fieldPolicyRevision < 1
                    || authorizationRevision < 1) {
                throw new IllegalArgumentException("trusted authority is invalid");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(route, "route");
            Pay003Models.token(contextKey, 160, "contextKey");
            Pay003Models.token(contextScopeKey, 160, "contextScopeKey");
            Pay003Models.token(decisionRevision, 200, "decisionRevision");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            permissions = Set.copyOf(permissions);
            if (!route.purpose().equals(purposeCode)
                    || !route.populationKind().equals(populationKind)
                    || !Objects.equals(route.fieldPolicyKey(), fieldPolicyKey)
                    || !permissions.contains("APP.HCM:VIEW")
                    || !permissions.contains(route.capability())
                    || (route.highRisk() && !"ELEVATED".equals(accessMode))) {
                throw new IllegalArgumentException(
                        "trusted route authority is inconsistent");
            }
        }
    }

    record Scope(
            UUID legalEntityId,
            UUID payrollGroupId,
            String projectionRevision,
            String scopeDigest) {

        Scope {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            Pay003Models.token(projectionRevision, 200, "projectionRevision");
            Pay003Models.digest(scopeDigest, "scopeDigest");
        }
    }

    record Actor(Subject subject, Scope scope) {
        Actor {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(scope, "scope");
        }

        void require(Pay003Route expected) {
            if (subject.route() != expected) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "The exact generated PAY-003 route is required.");
            }
        }

        boolean contains(UUID legalEntityId, UUID payrollGroupId) {
            return scope.legalEntityId().equals(legalEntityId)
                    && scope.payrollGroupId().equals(payrollGroupId);
        }
    }
}
