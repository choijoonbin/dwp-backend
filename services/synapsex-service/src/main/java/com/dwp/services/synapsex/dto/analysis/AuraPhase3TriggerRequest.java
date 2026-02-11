package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Aura Phase3: POST /aura/internal/cases/{caseId}/analysis-runs 요청 body
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraPhase3TriggerRequest {

    private UUID runId;
    private Long caseId;
    private String requestedBy;
    /** evidence/document/openItems/lineage 등 (BE 입력 패키지) */
    private JsonNode artifacts;
    private Callbacks callbacks;
    private Map<String, Object> options;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Callbacks {
        private String resultCallbackUrl;
        private Auth auth;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Auth {
        private String type;  // BEARER
        private String token;
    }
}
