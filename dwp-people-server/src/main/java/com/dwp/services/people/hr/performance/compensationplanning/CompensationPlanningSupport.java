package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared compensation operation names, internal contexts, and command errors. */
final class CompensationPlanningSupport {

    static final String CREATE = "modern.compplan.cycle.create";
    static final String REVISE = "modern.compplan.cycle.revise";
    static final String BUDGET = "modern.compplan.budget.allocate";
    static final String UPSERT_PROPOSAL = "modern.compplan.proposal.upsert";
    static final String SUBMIT_PROPOSAL = "modern.compplan.proposal.submit";
    static final String APPROVE = "modern.compplan.plan.approve";
    static final String PUBLISH = "modern.compplan.snapshot.publish";
    static final String CANCEL = "modern.compplan.cycle.cancel";
    static final String QUERY_CYCLE = "modern.compplan.cycle.query";
    static final String QUERY_CYCLES = "modern.compplan.cycles.query";
    static final String QUERY_PROPOSAL = "modern.compplan.proposal.query";
    static final String QUERY_PROPOSALS = "modern.compplan.proposals.query";

    static final String CYCLE_BASE =
            "/api/people/v1/hris/performance/compensation-planning/cycles";
    static final String APPROVE_ACTION = "hcm.compensation.plan.approve";
    static final String PROPOSE_ACTION = "hcm.compensation.plan.propose";
    static final String VIEW_ACTION = "hcm.compensation.plan.view";

    static <T> T cas(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (CompensationPlanningRepository.OptimisticConflict exception) {
            throw stale();
        }
    }

    static BaseException unavailable() {
        return new BaseException(
                ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The immutable compensation command result is unavailable.");
    }

    static BaseException stale() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "Compensation-planning version changed; refresh and retry.");
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    static BaseException conflict(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    record CommandContext(
            PeopleRequestContext.Actor actor,
            UUID subject,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority,
            String idempotencyKey,
            UUID correlationId,
            String requestHash,
            String operation,
            String originatingAction,
            Instant now,
            CompensationPlanningDtos.CommandReceipt replay) {
    }

    record QueryContext(
            PeopleRequestContext.Actor actor,
            UUID subject,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
    }

    record Reservation(
            UUID receiptId,
            CompensationPlanningDtos.CommandReceipt replay) {
    }
}
