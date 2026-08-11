package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.entity.IdentityAuditEvent;
import com.dwp.services.auth.repository.IdentityAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityAuditService {

    private final IdentityAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public IdentityAuditService(
            IdentityAuditEventRepository repository,
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
            Map<String, Object> before,
            Map<String, Object> after) {
        repository.save(IdentityAuditEvent.builder()
                .auditEventId(UUID.randomUUID())
                .tenantId(tenantId)
                .actorId(actorId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .correlationId(trimToNull(correlationId))
                .outcome("SUCCESS")
                .beforeSnapshot(toJson(before))
                .afterSnapshot(toJson(after))
                .occurredAt(Instant.now())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(
            Long tenantId,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            String reason,
            Map<String, Object> attemptedState) {
        repository.save(IdentityAuditEvent.builder()
                .auditEventId(UUID.randomUUID())
                .tenantId(tenantId)
                .actorId(actorId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .correlationId(trimToNull(correlationId))
                .outcome("DENIED")
                .reason(trimToNull(reason))
                .afterSnapshot(toJson(attemptedState))
                .occurredAt(Instant.now())
                .build());
    }

    @Transactional(readOnly = true)
    public IdentityAdminDtos.PageResult<IdentityAdminDtos.IdentityAuditEventResponse> list(
            Long tenantId,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<IdentityAuditEvent> result = repository.findByTenantId(
                tenantId,
                PageRequest.of(safePage, safeSize, Sort.by("occurredAt").descending()));
        return new IdentityAdminDtos.PageResult<>(
                result.stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private IdentityAdminDtos.IdentityAuditEventResponse toResponse(IdentityAuditEvent event) {
        return new IdentityAdminDtos.IdentityAuditEventResponse(
                event.getAuditEventId().toString(),
                "USER",
                event.getActorId(),
                event.getAction(),
                event.getTargetType(),
                event.getTargetId(),
                event.getOutcome(),
                event.getCorrelationId(),
                event.getOccurredAt());
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Identity audit snapshot serialization failed.",
                    exception);
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
