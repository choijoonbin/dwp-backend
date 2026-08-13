package com.dwp.services.platform.auditcontrol;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditControlDtos {
    private AuditControlDtos() { }

    public record Event(
            UUID eventId, Instant occurredAt, Instant ingestedAt, Long tenantId,
            String category, String action, String outcome, String severity, int riskScore,
            String actorType, String actorId, String actorPrincipal, String actorDisplayName,
            List<String> actorRoles, String sourceService, String sourceModule,
            String sourceInstance, String environment, String targetType, String targetId,
            String targetDisplayName, String reason, String correlationId, String traceId,
            String authenticationMethod, String policyId, String policyDecision,
            String approvalId, Map<String, Object> beforeState, Map<String, Object> afterState,
            List<String> changedFields, Map<String, Object> metadata,
            String retentionClass, String recordHash) { }

    public record EventPage(List<Event> content, int page, int size, long totalElements, int totalPages) { }
    public record Metric(String key, long count) { }
    public record TrendPoint(Instant bucket, long total, long highRisk, long denied) { }
    public record Summary(
            long totalEvents, long highRiskEvents, long deniedEvents, long failedEvents,
            long openFindings, long activeCases, int healthySources, int registeredSources) { }
    public record SourceHealth(
            String sourceService, Instant lastEventAt, Instant lastIngestedAt,
            long eventCount24h, long rejectedCount24h, String deliveryStatus, String lastError) { }
    public record Overview(
            AuditWindow window, Instant from, Instant to, Instant generatedAt, Summary summary,
            List<TrendPoint> trend, List<Metric> categories, List<Metric> outcomes,
            List<Metric> topActors, List<Finding> attention, List<SourceHealth> sources) { }

    public record Finding(
            UUID findingId, UUID eventId, String findingType, String ruleKey, String severity,
            int riskScore, String status, String title, String description, String sourceService,
            String actorId, String targetType, String targetId, int occurrenceCount,
            Instant firstSeenAt, Instant lastSeenAt, String assignedTo, UUID caseId,
            String resolution, Instant updatedAt) { }
    public record FindingUpdate(String status, String assignedTo, String resolution, UUID caseId) { }
    public record FindingContext(
            Finding finding, Event primaryEvent, List<Event> relatedEvents) { }

    public record AuditCase(
            UUID caseId, long caseNumber, String title, String description, String severity,
            String status, String ownerActorId, String resolution, Instant openedAt,
            Instant dueAt, String slaState, Instant closedAt,
            String createdBy, String updatedBy, Instant updatedAt,
            int linkedEvents, int linkedFindings) { }
    public record CaseCreate(String title, String description, String severity, String ownerActorId) { }
    public record CaseUpdate(
            String title, String description, String severity, String status,
            String ownerActorId, String resolution) { }
    public record CaseEventLink(UUID eventId, Instant occurredAt, String note) { }
    public record CaseEntity(
            String entityType, String entityId, String displayName, String relationship,
            int riskScore, Instant firstSeenAt, Instant lastSeenAt,
            Map<String, Object> attributes) { }
    public record CaseActivity(
            UUID activityId, String activityType, String actorId, String message,
            Map<String, Object> payload, Instant occurredAt) { }
    public record CaseTask(
            UUID taskId, String title, String description, String status, String priority,
            String ownerActorId, Instant dueAt, Instant completedAt,
            String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) { }
    public record CaseTaskCreate(
            String title, String description, String priority,
            String ownerActorId, Instant dueAt) { }
    public record CaseTaskUpdate(
            String title, String description, String status, String priority,
            String ownerActorId, Instant dueAt) { }
    public record CaseNoteCreate(String message) { }
    public record InvestigationSummary(
            int maxRiskScore, int openTasks, int overdueTasks,
            int evidenceCount, int findingCount, int entityCount) { }
    public record CaseWorkspace(
            AuditCase auditCase, InvestigationSummary summary,
            List<Finding> findings, List<Event> evidence,
            List<CaseEntity> entities, List<CaseActivity> activities,
            List<CaseTask> tasks) { }
    public record CaseClosureReport(
            UUID reportId, UUID caseId, long caseNumber, int reportVersion,
            String contentSha256, String generatedBy, Instant generatedAt,
            Map<String, Object> report) { }

    public record SavedSearch(
            UUID savedSearchId, String name, Map<String, Object> criteria,
            boolean shared, boolean editable, String ownerActorId,
            Instant createdAt, Instant updatedAt) { }
    public record SavedSearchRequest(
            String name, AuditWindow window, String category, String severity, String outcome,
            String sourceService, String actor, String query, boolean shared) { }

    public record RetentionPolicy(
            int standardRetentionDays, int extendedRetentionDays, int exportLimitRows,
            boolean requireExportReason, boolean integrityEnabled, int highRiskThreshold,
            String updatedBy, Instant updatedAt,
            UUID activeRevisionId, long activeRevisionNumber) { }
    public record RetentionPolicyUpdate(
            int standardRetentionDays, int extendedRetentionDays, int exportLimitRows,
            boolean requireExportReason, boolean integrityEnabled, int highRiskThreshold) { }
    public record PolicyApproval(
            UUID approvalId, String lifecycleState, String requestedBy,
            Instant requestedAt, Instant expiresAt, String decidedBy,
            Instant decidedAt, String decisionReason, long version) { }
    public record PolicyRevision(
            UUID revisionId, long revisionNumber, String lifecycleState,
            int standardRetentionDays, int extendedRetentionDays, int exportLimitRows,
            boolean requireExportReason, boolean integrityEnabled, int highRiskThreshold,
            UUID baselineRevisionId, UUID rollbackOfRevisionId, UUID incidentCaseId,
            String changeReason, Map<String, Object> diff, String contentSha256,
            String createdBy, Instant createdAt, String submittedBy, Instant submittedAt,
            String publishedBy, Instant publishedAt, long version,
            PolicyApproval approval) { }
    public record PolicyRevisionCreate(
            int standardRetentionDays, int extendedRetentionDays, int exportLimitRows,
            boolean requireExportReason, boolean integrityEnabled, int highRiskThreshold,
            String reason, UUID incidentCaseId) { }
    public record PolicyRevisionTransition(String reason, long version) { }
    public record PolicyRevisionDecision(String decision, String reason, long version) { }
    public record PolicyRollbackRequest(String reason, UUID incidentCaseId) { }
    public record IntegrityCheckpoint(
            UUID checkpointId, LocalDate checkpointDate, long recordCount,
            Instant firstEventAt, Instant lastEventAt, String rootHash,
            String checkpointHash, String signatureAlgorithm, String verificationStatus,
            Instant createdAt, Instant verifiedAt) { }
    public record ExportRequest(
            AuditWindow window, String category, String severity, String outcome,
            String sourceService, String actor, String query, String format, String reason) { }
    public record ExportJob(
            UUID exportJobId, String format, String status, Integer rowCount,
            String contentSha256, String errorMessage, Instant requestedAt,
            Instant completedAt, Instant expiresAt) { }
}
