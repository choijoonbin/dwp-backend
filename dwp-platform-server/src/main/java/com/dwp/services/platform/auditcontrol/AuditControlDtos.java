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

    public record AuditCase(
            UUID caseId, long caseNumber, String title, String description, String severity,
            String status, String ownerActorId, String resolution, Instant openedAt,
            Instant closedAt, String createdBy, String updatedBy, Instant updatedAt,
            int linkedEvents, int linkedFindings) { }
    public record CaseCreate(String title, String description, String severity, String ownerActorId) { }
    public record CaseUpdate(
            String title, String description, String severity, String status,
            String ownerActorId, String resolution) { }
    public record CaseEventLink(UUID eventId, Instant occurredAt, String note) { }

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
            String updatedBy, Instant updatedAt) { }
    public record RetentionPolicyUpdate(
            int standardRetentionDays, int extendedRetentionDays, int exportLimitRows,
            boolean requireExportReason, boolean integrityEnabled, int highRiskThreshold) { }
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
