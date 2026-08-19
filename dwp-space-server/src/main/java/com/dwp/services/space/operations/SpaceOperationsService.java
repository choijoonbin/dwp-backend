package com.dwp.services.space.operations;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.integration.SpaceEntitlementPort;
import com.dwp.services.space.security.SpaceRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SpaceOperationsService {

    private final SpaceOperationsRepository repository;
    private final SpaceGovernanceRepository governance;
    private final SpaceEntitlementPort entitlements;
    private final AuditOutboxRecorder audit;
    private final TransactionTemplate transactions;

    public SpaceOperationsService(
            SpaceOperationsRepository repository,
            SpaceGovernanceRepository governance,
            SpaceEntitlementPort entitlements,
            AuditOutboxRecorder audit,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.governance = governance;
        this.entitlements = entitlements;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public SpaceDtos.OperationsDashboard dashboard() {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        requireManage(subject, false);
        return dashboard(subject.tenantId());
    }

    public SpaceDtos.ReconciliationRunSummary reconcileManual(String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        requireManage(subject, true);
        return reconcile(subject, "MANUAL", correlationId);
    }

    public SpaceDtos.ReconciliationRunSummary reconcileRecovery(String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        requireManage(subject, true);
        return reconcile(subject, "RECOVERY", correlationId);
    }

    private SpaceDtos.ReconciliationRunSummary reconcile(
            SpaceRequestContext.Subject subject,
            String triggerType,
            String correlationId) {
        SpaceDtos.ReconciliationRunSummary result = run(
                subject.tenantId(), triggerType, subject.userId(), correlationId);
        audit.record(AuditEvent.builder()
                .tenantId(subject.tenantId())
                .category("ADMIN_CHANGE")
                .action("SPACE_RECONCILIATION_RUN")
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(Long.toString(subject.userId()))
                .actorRoles(List.copyOf(subject.roles()))
                .sourceService("dwp-space-server")
                .sourceModule("space-operations")
                .targetType("SPACE_RECONCILIATION_RUN")
                .targetId(result.runId().toString())
                .correlationId(correlationId)
                .afterState(Map.of(
                        "plannedCount", result.plannedCount(),
                        "expiredCount", result.expiredCount(),
                        "findingCount", result.findingCount()))
                .retentionClass("EXTENDED")
                .build());
        return result;
    }

    @Transactional
    public void retry(UUID syncItemId, String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        requireManage(subject, true);
        if (!repository.retry(syncItemId, subject.tenantId())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The entitlement delivery is not eligible for retry.");
        }
        audit.record(AuditEvent.builder()
                .tenantId(subject.tenantId())
                .category("ADMIN_CHANGE")
                .action("SPACE_ENTITLEMENT_RETRY_REQUESTED")
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(Long.toString(subject.userId()))
                .actorRoles(List.copyOf(subject.roles()))
                .sourceService("dwp-space-server")
                .sourceModule("space-operations")
                .targetType("SPACE_ENTITLEMENT_SYNC")
                .targetId(syncItemId.toString())
                .correlationId(correlationId)
                .retentionClass("EXTENDED")
                .build());
    }

    public void reconcileScheduled(long tenantId) {
        run(tenantId, "SCHEDULED", null, "space-reconciliation:" + UUID.randomUUID());
    }

    public int deliver(int limit, String workerId) {
        if (!entitlements.configured()) return 0;
        List<SpaceOperationsRepository.SyncItem> items = repository.claim(limit, workerId);
        for (SpaceOperationsRepository.SyncItem item : items) {
            try {
                SpaceEntitlementPort.Result result = entitlements.synchronize(
                        new SpaceEntitlementPort.Command(
                                item.tenantId(), item.sourceRef(),
                                "space-entitlement:" + item.syncItemId() + ":" + item.attemptCount(),
                                item.principalType(), item.principalRef(),
                                item.resourceKey(), item.resourceName(), item.permissionCode(),
                                "GRANTED".equals(item.desiredState()) ? "GRANT" : "REVOKE",
                                item.validUntil(),
                                "Space membership desired-state synchronization.",
                                item.actorUserId()));
                repository.markSucceeded(
                        item.syncItemId(), result.grantId(), result.lifecycleState());
            } catch (RuntimeException exception) {
                repository.markFailed(item, rootMessage(exception));
            }
        }
        return items.size();
    }

    @Transactional
    public void recordPolicyEvaluation(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            String policyType,
            String subjectType,
            String subjectRef,
            String decision,
            String enforcementMode,
            String riskLevel,
            String correlationId,
            Map<String, Object> evidence) {
        governance.recordPolicyEvaluation(
                subject.tenantId(), spaceId, policyType, subjectType, subjectRef,
                decision, enforcementMode, riskLevel, "POLICY",
                Long.toString(subject.userId()), correlationId, evidence);
    }

    private SpaceDtos.ReconciliationRunSummary run(
            long tenantId,
            String triggerType,
            Long requestedBy,
            String correlationId) {
        UUID runId = governance.startRun(tenantId, triggerType, requestedBy, correlationId);
        try {
            RunOutcome outcome = transactions.execute(status -> {
                SpaceOperationsRepository.PlanningResult planning = repository.plan(tenantId);
                int findings = governance.refreshFindings(tenantId);
                governance.completeRun(
                        runId, planning.plannedCount(), planning.expiredCount(), findings);
                return new RunOutcome(planning, findings);
            });
            if (outcome == null) throw new IllegalStateException("Reconciliation returned no result.");
            return governance.runs(tenantId, 20).stream()
                    .filter(candidate -> candidate.runId().equals(runId))
                    .findFirst()
                    .map(this::runSummary)
                    .orElseThrow();
        } catch (RuntimeException exception) {
            governance.failRun(runId, rootMessage(exception));
            throw exception;
        }
    }

    private SpaceDtos.OperationsDashboard dashboard(long tenantId) {
        SpaceGovernanceRepository.OperationsMetrics metrics = governance.metrics(tenantId);
        return new SpaceDtos.OperationsDashboard(
                Instant.now(), entitlements.configured(),
                new SpaceDtos.OperationsMetrics(
                        metrics.queuedDeliveries(), metrics.deadLetters(), metrics.openFindings(),
                        metrics.highRiskFindings(), metrics.ownerlessSpaces(),
                        metrics.overdueReviews(), metrics.synchronizedLast24Hours()),
                governance.runs(tenantId, 12).stream().map(this::runSummary).toList(),
                governance.findings(tenantId, 50).stream().map(value ->
                        new SpaceDtos.ReconciliationFindingSummary(
                                value.findingId(), value.spaceId(), value.membershipId(),
                                value.findingType(), value.severity(), value.lifecycleState(),
                                value.targetType(), value.targetRef(), value.title(), value.evidence(),
                                value.firstDetectedAt(), value.lastDetectedAt())).toList(),
                repository.syncItems(tenantId, 50).stream().map(value ->
                        new SpaceDtos.EntitlementSyncSummary(
                                value.syncItemId(), value.spaceId(), value.membershipId(),
                                value.principalType(), value.principalRef(), value.resourceKey(),
                                value.permissionCode(), value.desiredState(), value.deliveryState(),
                                value.attemptCount(), value.nextAttemptAt(), value.externalState(),
                                value.lastError(), value.lastAttemptAt(), value.synchronizedAt()))
                        .toList());
    }

    private SpaceDtos.ReconciliationRunSummary runSummary(
            SpaceGovernanceRepository.RunSummary value) {
        return new SpaceDtos.ReconciliationRunSummary(
                value.runId(), value.triggerType(), value.lifecycleState(),
                value.plannedCount(), value.expiredCount(), value.findingCount(),
                value.requestedBy(), value.summary(), value.startedAt(), value.completedAt());
    }

    private void requireManage(SpaceRequestContext.Subject subject, boolean mutation) {
        boolean permitted = mutation
                ? subject.has("ADMIN.SPACE_GOVERNANCE", "MANAGE")
                : subject.has("ADMIN.SPACE_GOVERNANCE", "VIEW", "MANAGE");
        if (!permitted) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    private record RunOutcome(
            SpaceOperationsRepository.PlanningResult planning,
            int findings) {
    }
}
