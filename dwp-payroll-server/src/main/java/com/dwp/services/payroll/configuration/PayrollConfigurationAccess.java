package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact PEP action and owner-population projection for PAY-006. */
final class PayrollConfigurationAccess {

    private PayrollConfigurationAccess() {
    }

    enum Action {
        WORKBENCH_VIEW("hcm.configuration.workbench.view"),
        ELEMENT_VERSION_CREATE("hcm.operations.payroll-inputs.update"),
        FORMULA_VERSION_CREATE("hcm.operations.payroll-inputs.update"),
        FORMULA_VALIDATE("hcm.operations.payroll-inputs.update"),
        FORMULA_SIMULATE("hcm.operations.payroll-inputs.update"),
        FORMULA_PUBLISH("hcm.configuration.policy.publish");

        private final String permission;

        Action(String permission) {
            this.permission = permission;
        }

        String permission() {
            return permission;
        }
    }

    record Actor(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Set<Action> actions,
            Set<UUID> legalEntityIds,
            Set<UUID> payrollGroupIds,
            String purposeCode,
            String scopeDigest,
            String fieldPolicyRevision,
            String scopeDecisionRevision,
            String authorizationDecisionRevision) {

        Actor {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException("tenantId and actorId must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
            legalEntityIds = Set.copyOf(
                    Objects.requireNonNull(legalEntityIds, "legalEntityIds"));
            payrollGroupIds = Set.copyOf(
                    Objects.requireNonNull(payrollGroupIds, "payrollGroupIds"));
            if (legalEntityIds.isEmpty() || payrollGroupIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "legal-entity and payroll-group population are required");
            }
            purposeCode = requireText(purposeCode, "purposeCode");
            scopeDigest = PayrollConfigurationModels.requireDigest(
                    scopeDigest, "scopeDigest");
            fieldPolicyRevision = requireRevision(
                    fieldPolicyRevision, "fieldPolicyRevision");
            scopeDecisionRevision = requireRevision(
                    scopeDecisionRevision, "scopeDecisionRevision");
            authorizationDecisionRevision = requireRevision(
                    authorizationDecisionRevision, "authorizationDecisionRevision");
        }

        void require(Action action, UUID legalEntityId, UUID payrollGroupId) {
            if (!actions.contains(action)
                    || !legalEntityIds.contains(legalEntityId)
                    || !payrollGroupIds.contains(payrollGroupId)) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "Payroll configuration action is outside the exact permission or population.");
            }
        }

        boolean allows(Action action) {
            return actions.contains(action);
        }

        boolean contains(UUID legalEntityId, UUID payrollGroupId) {
            return legalEntityIds.contains(legalEntityId)
                    && payrollGroupIds.contains(payrollGroupId);
        }

        private static String requireRevision(String value, String name) {
            String normalized = requireText(value, name);
            if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return normalized;
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank() || !value.equals(value.strip())) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }
    }
}
