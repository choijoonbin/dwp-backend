package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/synapse/cases/{caseId}/analysis 응답
 * Aura finalResult 신규 4필드(riskScore, violationClause, reasoningSummary, recommendedAction) 포함.
 */
@Data
@Builder
public class CaseAnalysisDto {

    private UUID runId;
    private BigDecimal score;
    private String severity;
    private String reasonText;
    /** Aura 신규: 위험 점수 0~100 */
    private Integer riskScore;
    /** Aura 신규: 위반 규정 조항 (예: 제11조 2항) */
    private String violationClause;
    /** Aura 신규: 판단 근거 요약 */
    private String reasoningSummary;
    /** Aura 신규: 권고 조치 요약 */
    private String recommendedAction;
    /** Aura 품질 근거: 문장-근거 매핑 */
    private JsonNode sentenceCitationMap;
    /** Aura 품질 근거: 품질 게이트 코드 목록 */
    private JsonNode qualityGateCodes;
    /** Aura 품질 근거: 점수 분해 */
    private JsonNode analysisScoreBreakdown;
    /** citation_id 매핑 원본 배열 */
    private JsonNode citations;
    /** grounded 여부(근거 인용 존재 여부) */
    private Boolean grounded;
    private JsonNode confidenceBreakdown;
    private List<Map<String, Object>> evidence;
    private List<Map<String, Object>> similarCases;
    private List<Map<String, Object>> ragRefs;
    private List<CaseActionProposalDto> proposals;
    /** 결과가 없을 때 true. FE TabEmptyState에 reason 표시용 */
    private Boolean empty;
    /** empty=true일 때 FE에 표시할 메시지 */
    private String reason;
}
