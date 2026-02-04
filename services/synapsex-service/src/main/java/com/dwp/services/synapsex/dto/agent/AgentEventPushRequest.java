package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
        @NotBlank(message = "tenantId는 필수입니다.")
        @JsonAlias("tenant_id")
        private String tenantId;

        @NotBlank(message = "timestamp는 필수입니다.")
        private String timestamp;

        @NotBlank(message = "stage는 필수입니다.")
        private String stage;  // SCAN|DETECT|EXECUTE|SIMULATE|ANALYZE|MATCH

        @NotBlank(message = "message는 필수입니다.")
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

        private Map<String, Object> payload;
    }
}
