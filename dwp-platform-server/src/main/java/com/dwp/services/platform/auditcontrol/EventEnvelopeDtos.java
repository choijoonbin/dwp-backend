package com.dwp.services.platform.auditcontrol;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EventEnvelopeDtos {
    private EventEnvelopeDtos() { }

    public record Envelope(
            UUID eventId,
            String eventType,
            String schemaVersion,
            Instant occurredAt,
            Instant ingestedAt,
            Long tenantId,
            String domain,
            String classification,
            String sourceService,
            String sourceModule,
            String subjectType,
            String subjectId,
            String subjectDisplayName,
            String actorType,
            String actorId,
            String actorDisplayName,
            String outcome,
            String severity,
            int riskScore,
            String correlationId,
            String causationId,
            String traceId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            Map<String, Object> metadata,
            String recordHash) { }

    public record Correlation(
            String correlationId,
            Instant firstOccurredAt,
            Instant lastOccurredAt,
            long eventCount,
            int domainCount,
            int serviceCount,
            List<String> domains,
            List<String> classifications,
            List<String> sourceServices,
            List<String> outcomes,
            String latestEventType,
            String latestSubjectType,
            String latestSubjectId,
            String latestSubjectDisplayName,
            String maxSeverity,
            int maxRiskScore,
            boolean attentionRequired) { }

    public record CorrelationPage(
            List<Correlation> content,
            int page,
            int size,
            long totalElements,
            int totalPages) { }

    public record CorrelationDetail(Correlation summary, List<Envelope> events) { }
}
