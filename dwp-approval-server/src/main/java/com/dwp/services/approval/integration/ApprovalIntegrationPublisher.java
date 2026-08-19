package com.dwp.services.approval.integration;

public interface ApprovalIntegrationPublisher {

    void publish(ApprovalIntegrationOutboxRepository.PendingEvent event);
}
