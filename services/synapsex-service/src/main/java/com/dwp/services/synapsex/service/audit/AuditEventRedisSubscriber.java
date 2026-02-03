package com.dwp.services.synapsex.service.audit;

import com.dwp.services.synapsex.dto.audit.AuditEventIngestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis 채널 audit:events:ingest 구독.
 * Aura에서 발행한 AuditEvent JSON 수신 → 파싱 → audit_event_log 저장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final AuditEventIngestService ingestService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            AuditEventIngestDto dto = objectMapper.readValue(payload, AuditEventIngestDto.class);
            ingestService.ingest(dto);
        } catch (Exception e) {
            log.warn("AuditEvent Redis message parse/ingest failed: {}", e.getMessage());
        }
    }
}
