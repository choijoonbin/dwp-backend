package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class CompensationPlanningAuthorization {

    static final String APPLICATION = "APP.HRIS";
    static final String DATA_RESOURCE = "DATA.HRIS_COMPENSATION_PLAN";
    static final String APPROVAL_RESOURCE = "ACTION.HRIS_COMPENSATION_PLAN_APPROVE";
    static final String PURPOSE = "COMPENSATION_PLANNING";
    static final String VIEW_CAPABILITY = "hcm.compensation.plan.view";
    static final String PROPOSE_CAPABILITY = "hcm.compensation.plan.propose";
    static final String APPROVE_CAPABILITY = "hcm.compensation.plan.approve";

    private CompensationPlanningAuthorization() {
    }

    static UUID requireSubject(PeopleRequestContext.Actor actor) {
        if (actor.personPublicId() == null) {
            throw forbidden("A governed compensation subject is required.");
        }
        return actor.personPublicId();
    }

    static void requireActorPermission(PeopleRequestContext.Actor actor, String action) {
        if (!actor.hasPermission(resourceForAction(action), action)) {
            throw forbidden("The compensation-planning permission is not granted.");
        }
    }

    static String resourceForAction(String action) {
        return "APPROVE".equals(action) ? APPROVAL_RESOURCE : DATA_RESOURCE;
    }

    static CompensationPlanningAuthorityPort.AuthorityEvidence verify(
            CompensationPlanningAuthorityPort.AuthorityRequest request,
            CompensationPlanningAuthorityPort.AuthorityEvidence evidence,
            boolean stepUpRequired,
            List<String> requiredFields) {
        if (evidence == null
                || evidence.tenantId() != request.tenantId()
                || evidence.actorId() != request.actorId()
                || !Objects.equals(evidence.subjectPrincipalPublicId(),
                request.subjectPrincipalPublicId())
                || !Objects.equals(evidence.applicationEntitlement(),
                request.applicationEntitlement())
                || !Objects.equals(evidence.resource(), request.resource())
                || !Objects.equals(evidence.action(), request.action())
                || !Objects.equals(evidence.operationId(), request.operationId())
                || !Objects.equals(evidence.capability(), request.capability())
                || !Objects.equals(evidence.purposeCode(), request.purposeCode())
                || evidence.populationScopeDigest() == null
                || !evidence.populationScopeDigest().matches("[0-9a-f]{64}")
                || evidence.targetPopulation() == null
                || evidence.fieldDecisions() == null
                || evidence.fieldPolicyRevision() <= 0
                || evidence.authorizationRevision() <= 0) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Compensation-planning authority evidence is incomplete.");
        }
        Map<String, CompensationPlanningAuthorityPort.FieldDecision> decisions =
                evidence.fieldDecisions();
        for (String field : requiredFields) {
            if (decisions.get(field)
                    != CompensationPlanningAuthorityPort.FieldDecision.VIEW) {
                throw forbidden("Compensation field policy denied the operation.");
            }
        }
        if (stepUpRequired && !evidence.stepUpSatisfied()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        }
        return evidence;
    }

    static void requireIndependentApprover(UUID approver, List<UUID> makers) {
        if (makers.contains(approver)) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "A proposal maker cannot approve or publish the plan.");
        }
    }

    static boolean permitsDetailField(
            PeopleRequestContext.Actor actor,
            CompensationPlanningAuthorityPort.AuthorityEvidence evidence,
            String field) {
        boolean hasPlanningDuty = actor.hasPermission(DATA_RESOURCE, "UPDATE")
                || actor.hasPermission(APPROVAL_RESOURCE, "APPROVE");
        return hasPlanningDuty
                && PURPOSE.equals(evidence.purposeCode())
                && evidence.fieldDecisions().get(field)
                == CompensationPlanningAuthorityPort.FieldDecision.VIEW;
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}
