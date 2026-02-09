package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * GET /api/synapse/cases/{caseId}/analysis 응답
 * 캐시된 분석 결과 (score, severity, reasonText, evidence, similarCases, ragRefs, proposals)
 */
@Data
@Builder
public class CaseAnalysisDto {

    private BigDecimal score;
    private String severity;
    private String reasonText;
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
