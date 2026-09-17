package com.dwp.services.people.hr;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class HrMailProposalOutcomeCoordinator implements HrMailProposalOutcomeOperations {

    private final HrMailProposalOutcomeClient client;
    private final HrMailProposalOutcomeOutboxRepository outbox;

    HrMailProposalOutcomeCoordinator(
            HrMailProposalOutcomeClient client,
            HrMailProposalOutcomeOutboxRepository outbox) {
        this.client = client;
        this.outbox = outbox;
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
}
