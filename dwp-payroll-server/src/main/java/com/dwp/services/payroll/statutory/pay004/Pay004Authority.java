package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Pay004Authority {

    private Pay004Authority() {
    }

    record Subject(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Pay004Route route,
            String purposeCode,
            String contextKey,
            String contextScopeKey,
            long fieldPolicyRevision,
            long authorizationRevision,
            String decisionRevision,
            Instant revalidateAt,
            String accessMode,
            String accessProfile,
            String populationKind,
            String fieldPolicyKey,
            Set<String> permissions,
            Set<String> duties) {
        Subject {
            if (tenantId < 1 || actorId < 1 || fieldPolicyRevision < 1
                    || authorizationRevision < 1) {
                throw new IllegalArgumentException("trusted authority is invalid");
            }
            Objects.requireNonNull(subjectPrincipalPublicId,
                    "subjectPrincipalPublicId");
            Objects.requireNonNull(route, "route");
            Pay004Models.token(contextKey, 160, "contextKey");
            Pay004Models.token(contextScopeKey, 160, "contextScopeKey");
            Pay004Models.revision(decisionRevision, "decisionRevision");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            permissions = Set.copyOf(permissions);
            duties = Set.copyOf(duties);
            if (!route.purpose().equals(purposeCode)
                    || !route.populationKind().equals(populationKind)
                    || !route.fieldPolicyKey().equals(fieldPolicyKey)
                    || !Set.of("NORMAL", "ELEVATED").contains(accessMode)
                    || !Pay004Models.PROFILE.equals(accessProfile)
                    || !permissions.contains("APP.HCM:VIEW")
                    || !permissions.contains(route.capability())
                    || !duties.contains(Pay004Models.DUTY)) {
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
            Pay004Models.revision(projectionRevision, "projectionRevision");
            Pay004Models.digest(scopeDigest, "scopeDigest");
        }
    }

    record Actor(Subject subject, Scope scope) {
        Actor {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(scope, "scope");
        }

        void require(Pay004Route expected) {
            if (subject.route() != expected) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "The exact generated PAY-004 route is required.");
            }
        }

        boolean contains(UUID legalEntityId, UUID payrollGroupId) {
            return scope.legalEntityId().equals(legalEntityId)
                    && scope.payrollGroupId().equals(payrollGroupId);
        }
    }
}
