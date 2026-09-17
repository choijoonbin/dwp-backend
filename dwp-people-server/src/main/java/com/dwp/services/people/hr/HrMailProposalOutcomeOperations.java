package com.dwp.services.people.hr;

import java.util.UUID;

interface HrMailProposalOutcomeOperations {

    HrMailProposalOutcomeOperations NOOP = new HrMailProposalOutcomeOperations() {
        @Override
        public HrMailProposalExecutionRepository.Claim claimExecution(
                long tenantId,
                long actorId,
                HrMailProposalBinding binding,
                HrDtos.CreateLeaveRequest request) {
            throw new IllegalStateException("Mail proposal outcome integration is unavailable.");
        }

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

        @Override
        public void completeExecution(
                long tenantId,
                long actorId,
                HrMailProposalBinding binding,
                String requestFingerprint,
                UUID leaveRequestId) {
            throw new IllegalStateException("Mail proposal outcome integration is unavailable.");
        }

        @Override
        public void releaseNotExecuted(
                long tenantId,
                long actorId,
                HrMailProposalBinding binding,
                String reasonCode,
                String correlationId) {
            throw new IllegalStateException("Mail proposal outcome integration is unavailable.");
        }
    };

    HrMailProposalExecutionRepository.Claim claimExecution(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request);

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

    void completeExecution(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            String requestFingerprint,
            UUID leaveRequestId);

    void releaseNotExecuted(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            String reasonCode,
            String correlationId);
}
