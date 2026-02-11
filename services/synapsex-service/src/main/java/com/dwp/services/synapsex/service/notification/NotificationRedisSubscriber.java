package com.dwp.services.synapsex.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Redis 패턴 workbench:* 구독(PSUBSCRIBE) — Aura 발행 메시지 수신 후 NotificationDto 변환, DB 저장, WebSocket 브로드캐스트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisSubscriber implements MessageListener {

    private static final String CHANNEL_CASE_ACTION = "workbench:case:action";
    private static final String CHANNEL_RAG_STATUS = "workbench:rag:status";
    private static final String TYPE_CASE_ACTION = "CASE_ACTION";
    private static final String TYPE_RAG_STATUS = "RAG_STATUS";

    private final ObjectMapper objectMapper;
    private final NotificationBroadcastService broadcastService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = message.getChannel() != null ? new String(message.getChannel(), StandardCharsets.UTF_8) : "";
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(body);
            Long tenantId = root.has("tenant_id") && !root.get("tenant_id").isNull()
                    ? root.get("tenant_id").asLong() : null;
            if (tenantId == null) {
                tenantId = 0L;
            }
            // Aura 규격 통일: timestamp → occurredAt (모든 채널, 없으면 at 또는 now)
            Instant occurredAt = Instant.now();
            if (root.has("timestamp") && !root.get("timestamp").isNull()) {
                try {
                    JsonNode ts = root.get("timestamp");
                    if (ts.isNumber()) occurredAt = Instant.ofEpochMilli(ts.asLong());
                    else if (ts.isTextual()) occurredAt = Instant.parse(ts.asText());
                } catch (Exception ignored) {}
            } else if (root.has("at") && !root.get("at").isNull()) {
                try {
                    occurredAt = Instant.parse(root.get("at").asText());
                } catch (Exception ignored) {}
            }

            // Aura 규격: category → type, message → content (우선 사용, 없으면 채널별 fallback)
            String type = root.has("category") && !root.get("category").isNull() ? root.get("category").asText() : null;
            String content = root.has("message") && !root.get("message").isNull() ? root.get("message").asText() : null;
            String title = root.has("title") && !root.get("title").isNull() ? root.get("title").asText() : null;

            if (CHANNEL_CASE_ACTION.equals(channel)) {
                if (type == null) type = TYPE_CASE_ACTION;
                if (content == null) {
                    String caseId = root.has("case_id") ? root.get("case_id").asText() : "";
                    boolean approved = root.has("approved") && root.get("approved").asBoolean();
                    content = String.format("케이스 %s %s", caseId, approved ? "승인" : "거절");
                }
                if (title == null) title = "조치 완료";
            } else if (CHANNEL_RAG_STATUS.equals(channel)) {
                if (type == null) type = TYPE_RAG_STATUS;
                if (content == null) {
                    String docId = root.has("rag_document_id") ? root.get("rag_document_id").asText()
                            : (root.has("doc_id") ? root.get("doc_id").asText() : (root.has("document_id") ? root.get("document_id").asText() : ""));
                    String status = root.has("status") ? root.get("status").asText() : "";
                    content = docId.isEmpty() ? (status.isEmpty() ? "RAG 처리 완료" : status) : String.format("문서 %s 상태: %s", docId, status.isEmpty() ? "완료" : status);
                }
                if (title == null) title = "RAG 문서 상태";
            } else {
                if (type == null) type = "GENERIC";
                if (content == null) content = body.length() > 200 ? body.substring(0, 200) + "…" : body;
                if (title == null) title = "알림";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            broadcastService.saveAndBroadcast(tenantId, null, title, content, type, channel, occurredAt, payload);
        } catch (Exception e) {
            log.warn("Notification Redis parse/broadcast failed channel={} {}", channel, e.getMessage());
        }
    }
}
