package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aura → BE 콜백 payload
 */
@Data
public class AuraCallbackPayload {

    private UUID runId;
    private String status;  // COMPLETED | FAILED
    private String auraTraceId;
    private List<Map<String, Object>> partialEvents;
    private FinalResult finalResult;

    @Data
    public static class FinalResult {
        private Double score;
        private String severity;
        private String reasonText;
        private JsonNode confidence;
        private List<Map<String, Object>> evidence;
        private List<Map<String, Object>> ragRefs;
        private List<Map<String, Object>> similar;
        /** proposals: BE dedup_key 기반 멱등 처리 */
        private List<ProposalItem> proposals;
    }

    @Data
    public static class ProposalItem {
        private String type;
        private String riskLevel;
        private String rationale;
        private JsonNode payload;
        /** Aura 스펙: createdAt 추가 */
        private Instant createdAt;
        /** Aura 스펙: FE 승인 플로우용. 승인 필요 여부 */
        private Boolean requiresApproval;
    }
}
