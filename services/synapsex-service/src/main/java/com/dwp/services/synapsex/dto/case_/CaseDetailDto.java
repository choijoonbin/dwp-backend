package com.dwp.services.synapsex.dto.case_;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    /** 스크리닝 결과(Detect/Aura). Aura 분석 시 분류 기준으로 사용. GET /agent-tools/cases/{id} 및 분석 run evidence에 포함. */
    @JsonProperty("caseType")
    @JsonAlias("case_type")
    private String caseType;
    /** 스크리닝 사유(agent_case.reason_text). Aura 분석 run evidence에 screening_reason_text로 전달 가능. */
    @JsonProperty("reasonText")
    @JsonAlias("screening_reason_text")
    private String reasonText;
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
    /** [추론 탭] Aura 생각 흐름. event_type=AGENT_STREAM만, 기술 로그 제외, 최신순. occurredAt은 ISO8601. */
    @JsonProperty("aiThoughts")
    private List<AiThoughtItemDto> aiThoughts;
    private EvidencePanelDto evidence;
    private ReasoningPanelDto reasoning;
    private ActionPanelDto action;

    /** [사고 과정] AGENT_STREAM 로그의 message 배열 (시간순, 기술 로그 제외). 없으면 []. */
    @JsonProperty("reasoningProcess")
    private List<String> reasoningProcess;
    /** [검토 로직] 규정 조항 리스트. 없으면 []. */
    @JsonProperty("logicCheckpoints")
    private List<LogicCheckpointDto> logicCheckpoints;
    /** [증거 맵] 그리드 매칭용 itemIdx/reason/severity. 없으면 []. */
    @JsonProperty("evidenceLinks")
    private List<EvidenceLinkDto> evidenceLinks;
    /** [분석 리포트] 최종 감사 의견·판정·액션 버튼. 없으면 {}. */
    @JsonProperty("finalReport")
    private FinalReportDto finalReport;
    /** [이력 탭] 상태 변경·분석 시작/종료·AGENT_STREAM 등 모든 이벤트 타입. 시간순(occurredAt ASC). 없으면 []. */
    @JsonProperty("activityHistory")
    private List<AiThoughtItemDto> activityHistory;

    /** DB에 저장된 최신 분석 점수(agent_case.score). FE가 실시간 대신 저장값을 신뢰할 수 있도록 단일 필드 제공. */
    @JsonProperty("analysisScore")
    private BigDecimal analysisScore;

    /** 규정 v2.0: evidence_json에 담긴 컨텍스트(근무/휴가, 업종, 한도초과). Aura metadata 전달/표시용. */
    @JsonProperty("context")
    private CaseContextDto context;

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

    /** 규정 v2.0: evidence_json → metadata 전달용 컨텍스트 (hr_status, mcc_code, budget_exceeded). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseContextDto {
        @JsonProperty("hrStatus")
        private String hrStatus;
        @JsonProperty("mccCode")
        private String mccCode;
        @JsonProperty("budgetExceeded")
        private Boolean budgetExceeded;
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
        /** 사실-규정 1:1 매핑 (Side-by-Side): [{ docId, itemId, chunkId }, ...]. API 응답 필드명: evidenceMapJson (camelCase) */
        @JsonProperty("evidenceMapJson")
        private JsonNode evidenceMapJson;
        /** 보고서 탭 종합 판정. evidenceMapJson.summary_verdict / summaryVerdict 또는 reasonText fallback */
        private String summaryVerdict;
        /** 보고서 탭 핵심 근거. evidenceMapJson.key_grounds / keyGrounds */
        private List<String> keyGrounds;
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

    /** [검토 로직] 규정 조항 1건. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogicCheckpointDto {
        private String clause;
        private String status;
        private String description;
    }

    /** [증거 맵] 그리드 행 좌표 1건. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceLinkDto {
        private String itemIdx;
        private String reason;
        private String severity;
    }

    /** [분석 리포트] 최종 감사 의견·판정·액션 버튼. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalReportDto {
        private String summary;
        private String verdict;
        private Boolean requestClarificationEnabled;
        private Boolean closeCaseEnabled;
    }
}
