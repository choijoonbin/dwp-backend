package com.dwp.services.synapsex.dto.case_;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A2) GET /cases/{caseId} 통합 응답 (Single Source of Truth).
 * fiDocItems, actionHistory, aiThoughts를 한 번에 반환하여 탭별 개별 호출 제거.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseDetailDto {

    private Long caseId;
    private String status;
    /** P0-2: 핵심 식별자 (sourceType, bukrs, belnr, gjahr, buzei, dedupKey) */
    private CaseKeysDto keys;
    /** P0-2: 관련 링크 (openItems, lineage) — FE 하드코딩 제거용 */
    private CaseLinksDto links;
    /** 전표 상세 내역 (buzei, hkont, wrbtr, sgtxt 준수). wrbtr은 숫자. */
    @JsonProperty("fiDocItems")
    private List<DocumentLineItemDto> fiDocItems;
    /** 조치 이력 (agent_case_action_history 기반). actionAt은 ISO8601. */
    @JsonProperty("actionHistory")
    private List<CaseActionHistoryItemRefDto> actionHistory;
    /** AI 추론 결과 (Aura/agent_activity_log 기반). occurredAt은 ISO8601. */
    @JsonProperty("aiThoughts")
    private List<AiThoughtItemDto> aiThoughts;
    private EvidencePanelDto evidence;
    private ReasoningPanelDto reasoning;
    private ActionPanelDto action;

    /** 조치 이력 1건. DB action_at/created_at → API actionAt/createdAt (camelCase). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseActionHistoryItemRefDto {
        private Long id;
        private String actionType;
        private String actorId;
        private String commentText;
        @JsonProperty("actionAt")
        private Instant actionAt;
        @JsonProperty("createdAt")
        private Instant createdAt;
    }

    /** AI 추론 1건 (Aura 연동). DB occurred_at → API occurredAt (camelCase). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiThoughtItemDto {
        private String stage;
        private String eventType;
        private String message;
        @JsonProperty("occurredAt")
        private Instant occurredAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseKeysDto {
        private String sourceType;
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String buzei;
        private String dedupKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseLinksDto {
        private String openItems;
        private String lineage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidencePanelDto {
        private DocumentOrOpenItemDto documentOrOpenItem;
        private ReversalChainSummaryDto reversalChainSummary;
        private List<Long> relatedPartyIds;
        /** P0-3: 금액 표시 (fi_doc_item wrbtr 합계 또는 fi_open_item open_amount) */
        private java.math.BigDecimal amount;
        /** P0-3: 통화 (fi_doc_header waers 또는 fi_open_item currency) */
        private String currency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalChainSummaryDto {
        private List<String> nodeDocKeys;
        private int edgeCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasoningPanelDto {
        private BigDecimal score;
        private String reasonText;
        private JsonNode evidenceJson;
        private JsonNode ragRefsJson;
        private ConfidenceBreakdownDto confidenceBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfidenceBreakdownDto {
        private Double anomalyScore;
        private Double patternMatch;
        private Double ruleCompliance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPanelDto {
        private List<String> availableActionTypes;
        private List<ActionSummaryDto> actions;
        private LineageLinkParamsDto lineageLinkParams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionSummaryDto {
        private Long actionId;
        private String actionType;
        private String status;
        private String createdAt;
        private String executedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageLinkParamsDto {
        private Long caseId;
        private String docKey;
        private Long rawEventId;
        private Long partyId;
    }
}
