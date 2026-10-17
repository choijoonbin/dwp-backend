package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PayrollPreparationAuthority {

    private PayrollPreparationAuthority() {
    }

    record Subject(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            PayrollPreparationRoute route,
            String capabilityContractKey,
            String purposeCode,
            String contextKey,
            String contextScopeKey,
            String populationKind,
            long fieldPolicyRevision,
            long authorizationRevision,
            String decisionRevision,
            Instant revalidateAt,
            String accessMode,
            Set<String> permissions) {

        Subject {
            if (tenantId < 1 || actorId < 1 || fieldPolicyRevision < 1
                    || authorizationRevision < 1) {
                throw new IllegalArgumentException("trusted revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            permissions = Set.copyOf(permissions);
            if (!route.purposeCode().equals(purposeCode)
                    || !route.populationKinds().contains(populationKind)
                    || !route.activeAccessModes().contains(accessMode)) {
                throw new IllegalArgumentException("trusted route evidence is inconsistent");
            }
        }
    }

    record Scope(
            String projectionRevision,
            String scopeDigest,
            Set<PopulationTuple> populations) {

        Scope {
            PayrollPreparationModels.token(
                    projectionRevision, 200, "projectionRevision");
            PayrollPreparationModels.digest(scopeDigest, "scopeDigest");
            populations = Set.copyOf(populations);
            if (populations.isEmpty()) {
                throw new IllegalArgumentException("trusted population scope is empty");
            }
        }
    }

    record PopulationTuple(
            UUID legalEntityId,
            UUID payrollGroupId,
            String contextScopeKey) {
        PopulationTuple {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            PayrollPreparationModels.token(contextScopeKey, 160, "contextScopeKey");
        }
    }

    record Actor(Subject subject, Scope scope) {
        Actor {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(scope, "scope");
        }

        boolean contains(UUID legalEntityId, UUID payrollGroupId) {
            return scope.populations().contains(new PopulationTuple(
                    legalEntityId, payrollGroupId, subject.contextScopeKey()));
        }

        void requireRead() {
            if (subject.route() != PayrollPreparationRoute.PAGE
                    && subject.route() != PayrollPreparationRoute.DATA) {
                throw forbidden();
            }
        }

        void requirePrepare() {
            if (subject.route() != PayrollPreparationRoute.PREPARE) {
                throw forbidden();
            }
        }

        private BaseException forbidden() {
            return new BaseException(ErrorCode.FORBIDDEN,
                    "The exact generated Payroll route does not authorize this operation.");
        }
    }
}
