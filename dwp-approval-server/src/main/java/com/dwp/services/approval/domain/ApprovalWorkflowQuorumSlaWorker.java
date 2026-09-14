package com.dwp.services.approval.domain;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dwp.approval.workflow-quorum.sla.enabled", havingValue = "true")
public class ApprovalWorkflowQuorumSlaWorker {
    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflowQuorumSlaWorker.class);
    private final ApprovalWorkflowQuorumFacade runtime;
    private final String owner = "approval-quorum-sla-" + UUID.randomUUID();
    private final int batchSize;
    private final int leaseSeconds;

    public ApprovalWorkflowQuorumSlaWorker(ApprovalWorkflowQuorumFacade runtime,
            @Value("${dwp.approval.workflow-quorum.sla.batch-size:50}") int batchSize,
            @Value("${dwp.approval.workflow-quorum.sla.lease-seconds:30}") int leaseSeconds) {
        if (batchSize < 1 || batchSize > 100 || leaseSeconds < 1 || leaseSeconds > 300) {
            throw new IllegalArgumentException("Workflow SLA requires bounded batch and lease settings.");
        }
        this.runtime = runtime;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${dwp.approval.workflow-quorum.sla.poll-delay-ms:2000}")
    public void poll() {
        try { runtime.pollSla(owner, leaseSeconds, batchSize); }
        catch (RuntimeException exception) {
            log.warn("Workflow SLA could not finalize current authority evidence; durable leases remain recoverable.");
        }
    }
}
