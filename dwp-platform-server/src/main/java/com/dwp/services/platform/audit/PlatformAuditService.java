package com.dwp.services.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PlatformAuditService {

    private static final int MAX_SNAPSHOT_LENGTH = 32_000;

    private final PlatformAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public PlatformAuditService(
            PlatformAuditEventRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void success(
            Long tenantId,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Object before,
            Object after) {
        repository.save(PlatformAuditEvent.builder()
                .auditEventId(UUID.randomUUID())
                .tenantId(tenantId)
                .actorType("USER")
                .actorId(actorId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .outcome("SUCCESS")
                .correlationId(correlationId)
                .beforeSnapshot(snapshot(before))
                .afterSnapshot(snapshot(after))
                .occurredAt(Instant.now())
                .build());
    }

    @Transactional(readOnly = true)
    public AuditPage list(Long tenantId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<PlatformAuditEvent> result = repository.findByTenantId(
                tenantId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt")));
        List<AuditEventResponse> content = result.stream()
                .map(event -> new AuditEventResponse(
                        event.getAuditEventId(),
                        event.getActorType(),
                        event.getActorId(),
                        event.getAction(),
                        event.getTargetType(),
                        event.getTargetId(),
                        event.getOutcome(),
                        event.getCorrelationId(),
                        event.getOccurredAt()))
                .toList();
        return new AuditPage(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private String snapshot(Object value) {
        if (value == null) return null;
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.length() <= MAX_SNAPSHOT_LENGTH
                    ? json
                    : json.substring(0, MAX_SNAPSHOT_LENGTH);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit snapshot serialization failed.", exception);
        }
    }

    public record AuditEventResponse(
            UUID auditEventId,
            String actorType,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String correlationId,
            Instant occurredAt) {
    }

    public record AuditPage(
            List<AuditEventResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
