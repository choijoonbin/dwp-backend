package com.dwp.services.approval.incidents;

import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IncidentModels {
    private IncidentModels() {
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum IncidentStatus {
        OPEN, INVESTIGATING, MITIGATING, MONITORING, RESOLVED, CLOSED
    }

    public enum SourceKind {
        DELIVERY, CONNECTOR, POLICY, WORKFLOW, MANUAL
    }

    public enum PlanKind {
        REPLAY, RECONCILE, REASSIGN, MIXED
    }

    public enum PlanState {
        DRAFT, VALIDATED, BLOCKED, EXECUTING, PARTIAL,
        COMPLETED, FAILED, UNKNOWN_REMOTE_OUTCOME
    }

    public enum StageState {
        PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN_REMOTE_OUTCOME
    }

    public enum ActionKind {
        REPLAY, RECONCILE, REASSIGN, VERIFY
    }

    public enum TargetType {
        OUTBOX_EVENT, APPROVAL_TASK, CONNECTOR, POLICY
    }

    public record Context(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            UUID actorPersonPublicId,
            String idempotencyKey) {
        public Context {
            if (tenantId < 1 || actorUserId < 1 || actorPersonPublicId == null
                    || resourceSetKey == null
                    || !resourceSetKey.matches("RS_[A-Z0-9_]{1,76}")
                    || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
                throw IncidentRejected.invalid("Current incident command context is invalid.");
            }
        }

        public static Context current(String idempotencyKey) {
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            if (actor.tenantId() == null || actor.userId() == null) {
                throw IncidentRejected.forbidden("Current incident authority is incomplete.");
            }
            return new Context(actor.tenantId(),
                    ApprovalManagementScopeContext.requireResourceSetKey(),
                    actor.userId(), actor.personPublicId(), idempotencyKey);
        }
    }

    public record OpenIncident(
            UUID incidentId,
            String incidentKey,
            String title,
            Severity severity,
            SourceKind sourceKind,
            String sourceReference) {
    }

    public record IncidentView(
            UUID incidentId,
            String incidentKey,
            String title,
            Severity severity,
            IncidentStatus status,
            SourceKind sourceKind,
            String sourceReference,
            long version,
            Instant openedAt,
            Instant updatedAt,
            Instant resolvedAt) {
    }

    public record StatusCommand(
            IncidentStatus status,
            long expectedVersion,
            String summary,
            String evidenceSha256) {
    }

    public record DiagnosticCommand(
            UUID diagnosticId,
            String diagnosticKind,
            Map<String, Object> payload,
            String sourceRevision,
            Instant observedAt,
            long expectedIncidentVersion) {
    }

    public record DiagnosticView(
            UUID diagnosticId,
            String diagnosticKind,
            Map<String, Object> redactedPayload,
            String payloadSha256,
            String sourceRevision,
            Instant observedAt) {
    }

    public record StageDraft(
            int stageNumber,
            ActionKind actionKind,
            TargetType targetType,
            UUID targetId,
            long expectedTargetVersion) {
    }

    public record CreatePlan(
            UUID planId,
            PlanKind planKind,
            Map<String, Object> targetSnapshot,
            List<StageDraft> stages,
            long expectedIncidentVersion) {
    }

    public record DryRunObservation(
            long expectedPlanVersion,
            boolean executable,
            Map<String, Object> result,
            String evidenceSha256) {
    }

    public record StageStart(
            int stageNumber,
            long expectedPlanVersion,
            long expectedStageVersion,
            String executionKey) {
    }

    public record StageCompletion(
            int stageNumber,
            long expectedPlanVersion,
            long expectedStageVersion,
            SignedExecutionReceipt receipt) {
    }

    public record SignedExecutionReceipt(
            String evidencePayloadBase64Url,
            String evidenceSignatureBase64Url) {
    }

    public record ExecutionRequest(
            long tenantId,
            String resourceSetKey,
            UUID incidentId,
            UUID planId,
            int stageNumber,
            long expectedPlanVersion,
            long expectedStageVersion,
            ActionKind actionKind,
            TargetType targetType,
            UUID targetId,
            long expectedTargetVersion,
            String executionKey) {
    }

    record VerifiedStageCompletion(
            int stageNumber,
            long expectedPlanVersion,
            long expectedStageVersion,
            StageState state,
            Map<String, Object> result,
            String evidenceSha256,
            Instant completedAt,
            String receiptIssuer,
            String receiptKeyId,
            String receiptVerificationReference) {
    }

    public record StageView(
            int stageNumber,
            ActionKind actionKind,
            TargetType targetType,
            UUID targetId,
            long expectedTargetVersion,
            StageState state,
            String executionKey,
            int attempt,
            Map<String, Object> result,
            String evidenceSha256,
            long version,
            Instant startedAt,
            Instant completedAt,
            String receiptIssuer,
            String receiptKeyId,
            String receiptVerificationReference) {
    }

    public record PlanView(
            UUID incidentId,
            UUID planId,
            PlanKind planKind,
            PlanState state,
            Map<String, Object> targetSnapshot,
            String targetSha256,
            Map<String, Object> dryRunResult,
            String dryRunEvidenceSha256,
            long version,
            List<StageView> stages,
            Instant completedAt) {
    }

    public record PostmortemCommand(
            UUID postmortemId,
            String summary,
            List<String> contributingFactors,
            List<String> correctiveActions,
            String evidenceSha256,
            long expectedIncidentVersion) {
    }

    public record PostmortemView(
            UUID postmortemId,
            String summary,
            List<String> contributingFactors,
            List<String> correctiveActions,
            String evidenceSha256,
            long version,
            Instant recordedAt) {
    }

    public record TimelineEntry(
            long sequence,
            String eventType,
            String statusBefore,
            String statusAfter,
            String summary,
            String evidenceSha256,
            long actorUserId,
            Instant occurredAt) {
    }

    public record IncidentDetail(
            IncidentView incident,
            List<TimelineEntry> timeline,
            List<DiagnosticView> diagnostics,
            List<PlanView> recoveryPlans,
            PostmortemView postmortem) {
    }

    public record ReconcileCommand(long expectedVersion) {
    }

    public enum ReportFormat {
        EVIDENCE_JSON
    }

    public record ReportCommand(
            UUID reportId,
            ReportFormat format,
            List<UUID> deadLetterIds,
            long expectedIncidentVersion) {
        public ReportCommand {
            deadLetterIds = deadLetterIds == null ? List.of() : List.copyOf(deadLetterIds);
        }
    }

    public record IncidentReport(
            UUID reportId,
            UUID incidentId,
            long incidentVersion,
            ReportFormat format,
            Map<String, Object> payload,
            String reportSha256,
            long generatedBy,
            Instant generatedAt) {
    }

    public record DeadLetterView(
            UUID outboxId,
            UUID eventId,
            UUID requestId,
            String eventType,
            String status,
            int attemptCount,
            int manualRetryCount,
            long version,
            String recoveryAssignmentState,
            String errorSha256,
            Instant availableAt,
            Instant lockedUntil,
            Instant updatedAt,
            String canonicalReplayPath) {
    }
}
