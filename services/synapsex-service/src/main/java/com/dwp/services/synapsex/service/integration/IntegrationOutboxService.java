package com.dwp.services.synapsex.service.integration;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.IntegrationOutbox;
import com.dwp.services.synapsex.repository.IntegrationOutboxRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Integration Outbox 서비스.
 * 스펙: INTEGRATION_OUTBOX_ENQUEUE, INTEGRATION_RESULT_UPDATE 감사 이벤트 기록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationOutboxService {

    private final IntegrationOutboxRepository outboxRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public IntegrationOutbox enqueue(Long tenantId, String targetSystem, String eventType, String eventKey,
                                     Map<String, Object> payload, Long actorUserId,
                                     String ipAddress, String userAgent, String gatewayRequestId) {
        IntegrationOutbox outbox = IntegrationOutbox.builder()
                .tenantId(tenantId)
                .targetSystem(targetSystem)
                .eventType(eventType)
                .eventKey(eventKey)
                .payload(payload != null ? payload : Map.of())
                .status("PENDING")
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        outbox = outboxRepository.save(outbox);

        auditWriter.logIntegrationEvent(tenantId,
                AuditEventConstants.TYPE_INTEGRATION_OUTBOX_ENQUEUE,
                "INTEGRATION_OUTBOX",
                String.valueOf(outbox.getOutboxId()),
                actorUserId,
                AuditEventConstants.OUTCOME_SUCCESS,
                null,
                Map.of("outboxId", outbox.getOutboxId(), "eventType", eventType, "eventKey", eventKey),
                ipAddress, userAgent, gatewayRequestId);

        return outbox;
    }

    @Transactional
    public IntegrationOutbox updateResult(Long tenantId, Long outboxId, String status, String resultMessage,
                                          Long actorUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        IntegrationOutbox outbox = outboxRepository.findById(outboxId)
                .filter(o -> tenantId.equals(o.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Outbox not found: " + outboxId));

        String oldStatus = outbox.getStatus();
        outbox.setStatus(status != null ? status : "PROCESSED");
        outbox.setUpdatedAt(Instant.now());
        if (resultMessage != null) {
            outbox.setLastError(resultMessage);
        }
        outbox = outboxRepository.save(outbox);

        auditWriter.logIntegrationEvent(tenantId,
                AuditEventConstants.TYPE_INTEGRATION_RESULT_UPDATE,
                "INTEGRATION_OUTBOX",
                String.valueOf(outboxId),
                actorUserId,
                AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("status", oldStatus),
                Map.of("status", outbox.getStatus(), "result", resultMessage != null ? resultMessage : "OK"),
                ipAddress, userAgent, gatewayRequestId);

        return outbox;
    }
}
