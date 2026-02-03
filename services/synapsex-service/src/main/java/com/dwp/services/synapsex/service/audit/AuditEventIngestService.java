package com.dwp.services.synapsex.service.audit;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.audit.AuraEventStageMapper;
import com.dwp.services.synapsex.dto.audit.AuditEventIngestDto;
import com.dwp.services.synapsex.entity.AgentActivityLog;
import com.dwp.services.synapsex.entity.AuditEventLog;
import com.dwp.services.synapsex.repository.AgentActivityLogRepository;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Redis Pub/Sub으로 수신한 AuditEvent를 audit_event_log에 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventIngestService {

    private final AuditEventLogRepository repository;
    private final AgentActivityLogRepository agentActivityLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void ingest(AuditEventIngestDto dto) {
        if (dto == null || dto.getTenantId() == null) {
            log.warn("AuditEvent ingest skipped: tenantId required");
            return;
        }
        try {
            String stage = AuraEventStageMapper.toStage(dto.getEventType());
            Instant occurredAt = parseCreatedAt(dto.getCreatedAt());
            if (occurredAt == null) occurredAt = Instant.now();

            AgentActivityLog activityLog = AgentActivityLog.builder()
                    .tenantId(dto.getTenantId())
                    .stage(stage)
                    .eventType(dto.getEventType())
                    .resourceType(dto.getResourceType())
                    .resourceId(dto.getResourceId())
                    .occurredAt(occurredAt)
                    .actorAgentId(dto.getActorAgentId())
                    .actorUserId(dto.getActorUserId())
                    .actorDisplayName(dto.getActorDisplayName())
                    .metadataJson(dto.getEvidenceJson() != null ? dto.getEvidenceJson() : dto.getAfterJson())
                    .createdAt(Instant.now())
                    .build();
            agentActivityLogRepository.save(activityLog);

            AuditEventLog entity = toEntity(dto, stage, occurredAt);
            repository.save(entity);
            log.debug("AuditEvent ingested: tenantId={}, eventType={}, stage={}, resourceId={}",
                    dto.getTenantId(), dto.getEventType(), stage, dto.getResourceId());
        } catch (Exception e) {
            log.warn("AuditEvent ingest failed: tenantId={}, error={}", dto.getTenantId(), e.getMessage());
        }
    }

    private AuditEventLog toEntity(AuditEventIngestDto dto, String stage, Instant occurredAt) {
        return AuditEventLog.builder()
                .tenantId(dto.getTenantId())
                .eventCategory(AuditEventConstants.CATEGORY_AGENT)
                .eventType(stage != null ? stage : "ANALYZE")
                .resourceType(dto.getResourceType())
                .resourceId(dto.getResourceId())
                .createdAt(occurredAt != null ? occurredAt : Instant.now())
                .actorType(dto.getActorType() != null ? dto.getActorType() : AuditEventConstants.ACTOR_AGENT)
                .actorUserId(dto.getActorUserId())
                .actorAgentId(dto.getActorAgentId())
                .actorDisplayName(dto.getActorDisplayName())
                .channel(dto.getChannel() != null ? dto.getChannel() : AuditEventConstants.CHANNEL_AGENT)
                .outcome(dto.getOutcome() != null ? dto.getOutcome() : AuditEventConstants.OUTCOME_SUCCESS)
                .severity(dto.getSeverity() != null ? dto.getSeverity() : AuditEventConstants.SEVERITY_INFO)
                .beforeJson(dto.getBeforeJson())
                .afterJson(dto.getAfterJson())
                .diffJson(dto.getDiffJson())
                .evidenceJson(dto.getEvidenceJson())
                .tags(dto.getTags())
                .ipAddress(dto.getIpAddress())
                .userAgent(dto.getUserAgent())
                .gatewayRequestId(dto.getGatewayRequestId())
                .traceId(dto.getTraceId())
                .spanId(dto.getSpanId())
                .build();
    }

    private Instant parseCreatedAt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            log.debug("CreatedAt parse failed: {}", s);
            return null;
        }
    }
}
