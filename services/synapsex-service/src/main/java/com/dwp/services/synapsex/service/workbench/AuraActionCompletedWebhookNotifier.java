package com.dwp.services.synapsex.service.workbench;

import com.dwp.services.synapsex.event.ActionCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 조치 완료 시 Aura 플랫폼에 웹훅 POST (선택 사항).
 * 설정된 URL이 있을 때만 호출되며, Redis와 동일한 payload를 전송하여 후속 분석/알림에 활용.
 */
@Slf4j
@Component
public class AuraActionCompletedWebhookNotifier {

    @Value("${aura.webhook.action-completed-url:}")
    private String actionCompletedUrl;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public AuraActionCompletedWebhookNotifier(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * URL이 설정된 경우에만 Aura에 POST. 실패 시 로그만 남기고 예외 전파하지 않음.
     */
    public void notifyIfConfigured(ActionCompletedEvent event, Object newKpiSummary) {
        if (actionCompletedUrl == null || actionCompletedUrl.isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = buildPayload(event, newKpiSummary);
            String json = objectMapper.writeValueAsString(payload);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(actionCompletedUrl, new HttpEntity<>(json, headers), String.class);
            log.debug("Aura action-completed webhook sent: case_id={}", event.getCaseIdString());
        } catch (JsonProcessingException e) {
            log.warn("Aura webhook payload serialization failed: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Aura action-completed webhook failed: {} {}", actionCompletedUrl, e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(ActionCompletedEvent event, Object newKpiSummary) {
        String at = Instant.now().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "case_action_completed");
        payload.put("case_id", event.getCaseIdString());
        payload.put("request_id", event.getRequestId());
        payload.put("executor_id", event.getExecutorId() != null ? event.getExecutorId() : "SYSTEM");
        payload.put("action_type", event.getActionType());
        payload.put("approved", event.isApproved());
        payload.put("status_code", event.isApproved() ? "APPROVED" : "REJECTED");
        payload.put("history_id", event.getHistoryId());
        payload.put("fi_doc_updated", event.getFiDocUpdated());
        payload.put("at", at);
        payload.put("tenant_id", event.getTenantId() != null ? event.getTenantId() : 0L);
        payload.put("action_id", event.getActionId() != null ? event.getActionId() : 0L);
        if (newKpiSummary != null) {
            payload.put("new_kpi_summary", objectMapper.convertValue(newKpiSummary, Map.class));
        }
        return payload;
    }
}
