package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aura → Synapse REST push (Prompt C)
 * POST /api/synapse/agent/events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEventPushRequest {

    @NotNull(message = "events는 필수입니다.")
    @Valid
    private List<AgentEventItem> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentEventItem {
        @JsonAlias("tenant_id")
        private String tenantId;

        @JsonAlias("occurred_at")
        private String timestamp;

        private String stage;  // SCAN|DETECT|EXECUTE|SIMULATE|ANALYZE|MATCH

        private String message;

        @JsonAlias("case_key")
        private String caseKey;

        @JsonAlias("case_id")
        private String caseId;

        private String severity;  // INFO|WARN|ERROR

        @JsonAlias("trace_id")
        private String traceId;

        @JsonAlias("action_id")
        private String actionId;

        /** 표준 AGENT_EVENT 필드 (top-level 수용) */
        @JsonAlias("event_type")
        private String eventType;
        private String node;
        private String tool;
        @JsonAlias("decision_code")
        private String decisionCode;
        @JsonAlias("input_hash")
        private String inputHash;
        @JsonAlias("output_ref")
        private String outputRef;
        @JsonAlias("evidence_ids")
        private List<String> evidenceIds;
        @JsonAlias("run_id")
        private String runId;
        @JsonAlias("latency_ms")
        private Long latencyMs;
        @JsonAlias("summary_message")
        private String summaryMessage;
        @JsonAlias("error_code")
        private String errorCode;
        @JsonAlias("error_message")
        private String errorMessage;

        /** Aura 이벤트 메타데이터. thought_stream, reasoning 등 — metadata_json 키로도 수신 가능 */
        @JsonAlias("metadata_json")
        private Map<String, Object> payload;
    }
}
