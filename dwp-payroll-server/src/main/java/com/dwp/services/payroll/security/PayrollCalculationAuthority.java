package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted request evidence and Payroll-owned population projection. */
public final class PayrollCalculationAuthority {

    private PayrollCalculationAuthority() {
    }

    public enum Action { CALCULATE, RUN_VIEW, APPROVE, FINALIZE, RECEIPT_VIEW }

    public record Subject(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Action routeAction,
            String routeContractKey,
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

        public Subject {
            if (tenantId < 1 || actorId < 1 || fieldPolicyRevision < 1
                    || authorizationRevision < 1) {
                throw new IllegalArgumentException("trusted numeric evidence must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(routeAction, "routeAction");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            permissions = Set.copyOf(permissions);
        }

        public void require(Action expected) {
            if (routeAction != expected) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "The exact generated Payroll route does not authorize this operation.");
            }
        }
    }

    public record PopulationTuple(
            UUID legalEntityId,
            UUID payrollGroupId,
            String populationKind,
            String contextScopeKey) {

        public PopulationTuple {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            if (populationKind == null || populationKind.isBlank()
                    || contextScopeKey == null || contextScopeKey.isBlank()) {
                throw new IllegalArgumentException("population tuple is incomplete");
            }
        }
    }

    public record Scope(
            Set<PopulationTuple> populations,
            String projectionRevision,
            String scopeDigest) {

        public Scope {
            populations = Set.copyOf(populations);
            if (populations.isEmpty()
                    || projectionRevision == null || projectionRevision.isBlank()
                    || scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Payroll scope evidence is incomplete");
            }
        }
    }

    public record Actor(Subject subject, Scope scope) {
        public Actor {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(scope, "scope");
        }

        public long tenantId() { return subject.tenantId(); }

        public long actorId() { return subject.actorId(); }

        public UUID subjectPrincipalPublicId() { return subject.subjectPrincipalPublicId(); }

        public void require(Action action) { subject.require(action); }

        public boolean contains(UUID legalEntityId, UUID payrollGroupId) {
            return scope.populations().contains(new PopulationTuple(
                    legalEntityId, payrollGroupId, subject.populationKind(),
                    subject.contextScopeKey()));
        }
    }
}
