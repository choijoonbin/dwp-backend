package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * case_analysis_result 1건 요약. Workbench 상세 응답의 latestAnalysis용.
 * Aura finalResult 신규 4필드 포함.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkbenchAnalysisResultDto {

    private UUID runId;
    private BigDecimal score;
    private String severity;
    private String reasonText;
    /** Aura 신규: 위험 점수 0~100 */
    private Integer riskScore;
    /** Aura 신규: 위반 규정 조항 */
    private String violationClause;
    /** Aura 신규: 판단 근거 요약 */
    private String reasoningSummary;
    /** Aura 신규: 권고 조치 요약 */
    private String recommendedAction;
    private JsonNode confidenceJson;
    private JsonNode evidenceJson;
    private JsonNode similarJson;
    private JsonNode ragRefsJson;
    /** 사실-규정 1:1 매핑 (Side-by-Side): [{ docId, itemId, chunkId }, ...] */
    private JsonNode evidenceMapJson;
    private Instant createdAt;
}
