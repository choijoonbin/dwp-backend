package com.dwp.services.people.hr;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class HrMailProposalOutcomeCoordinator implements HrMailProposalOutcomeOperations {

    private final HrMailProposalOutcomeClient client;
    private final HrMailProposalOutcomeOutboxRepository outbox;
    private final HrMailProposalExecutionRepository executions;

    HrMailProposalOutcomeCoordinator(
            HrMailProposalOutcomeClient client,
            HrMailProposalOutcomeOutboxRepository outbox,
            HrMailProposalExecutionRepository executions) {
        this.client = client;
        this.outbox = outbox;
        this.executions = executions;
    }

    @Override
    public HrMailProposalExecutionRepository.Claim claimExecution(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request) {
        return executions.claim(tenantId, actorId, binding, request);
    }

    @Override
    public void preflight(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request) {
        client.preflight(tenantId, actorId, binding, request);
    }

    @Override
    public void enqueueExecuted(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            UUID leaveRequestId,
            String correlationId) {
        outbox.enqueue(
                tenantId,
                actorId,
                binding,
                leaveRequestId,
                "hr-leave-request:" + leaveRequestId,
                correlationId);
    }

    @Override
    public void completeExecution(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            String requestFingerprint,
            UUID leaveRequestId) {
        executions.complete(
                tenantId, actorId, binding, requestFingerprint, leaveRequestId);
    }

    @Override
    public void releaseNotExecuted(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            String reasonCode,
            String correlationId) {
        client.releaseNotExecuted(
                tenantId, actorId, binding, reasonCode, correlationId);
    }
}
