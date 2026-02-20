package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aura → BE 콜백 payload
 * caseId 등 Aura가 추가로 보내는 필드는 무시 (runId로 BE가 조회)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuraCallbackPayload {

    private UUID runId;
    private Long caseId;
    private String status;  // COMPLETED | FAILED
    private String auraTraceId;
    private List<Map<String, Object>> partialEvents;
    /** Phase2: 단일 finalResult */
    private FinalResult finalResult;
    /** Phase3: analysis + proposals + meta */
    private AnalysisBlock analysis;
    private List<ProposalItem> proposals;
    private Map<String, Object> meta;
    /** Phase3 FAILED 시. Aura가 문자열 또는 객체 { message, stage } 등으로 보낼 수 있음 → BE에서 문자열로 정규화 저장 */
    private JsonNode error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalysisBlock {
        private Double score;
        private String severity;
        private String reasonText;
        private Object confidence;
        private List<Map<String, Object>> evidence;
        private List<Map<String, Object>> ragRefs;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        /** Aura 신규: 위험 점수 0~100 (기존 score 0~1을 100 단위로 변환) */
        private Number risk_score;
        /** Aura 신규: 위반 규정 조항 (예: "제11조 2항", 없으면 "") */
        private String violation_clause;
        /** Aura 신규: 판단 근거 요약 (reasonText와 동일 내용) */
        private String reasoning_summary;
        /** Aura 신규: 권고 조치 요약 (proposals의 rationale을 "; "로 이어붙인 문자열) */
        private String recommended_action;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProposalItem {
        private String type;
        private String riskLevel;
        private String rationale;
        private JsonNode payload;
        /** Aura 스펙: createdAt 추가. 타임존 없을 때 UTC 가정 (LenientInstantDeserializer) */
        @JsonDeserialize(using = LenientInstantDeserializer.class)
        private Instant createdAt;
        /** Aura 스펙: FE 승인 플로우용. 승인 필요 여부 */
        private Boolean requiresApproval;
    }
}
