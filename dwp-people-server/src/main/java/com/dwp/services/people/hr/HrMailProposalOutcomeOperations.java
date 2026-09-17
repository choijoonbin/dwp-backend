package com.dwp.services.people.hr;

import java.util.UUID;

interface HrMailProposalOutcomeOperations {

    HrMailProposalOutcomeOperations NOOP = new HrMailProposalOutcomeOperations() {
        @Override
        public void preflight(
                long tenantId,
                long actorId,
                HrMailProposalBinding binding,
                HrDtos.CreateLeaveRequest request) {
            throw new IllegalStateException("Mail proposal outcome integration is unavailable.");
        }

        @Override
        public void enqueueExecuted(
                long tenantId,
                long actorId,
                HrMailProposalBinding binding,
                UUID leaveRequestId,
                String correlationId) {
            throw new IllegalStateException("Mail proposal outcome integration is unavailable.");
        }
    };

    void preflight(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request);

    void enqueueExecuted(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            UUID leaveRequestId,
            String correlationId);
}
