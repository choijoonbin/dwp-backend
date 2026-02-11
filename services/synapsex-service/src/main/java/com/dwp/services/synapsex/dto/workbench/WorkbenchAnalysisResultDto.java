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
    private JsonNode confidenceJson;
    private JsonNode evidenceJson;
    private JsonNode similarJson;
    private JsonNode ragRefsJson;
    private Instant createdAt;
}
