package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Aura POST /aura/cases/{caseId}/analysis-runs 호출용 요청
 * Aura 스펙: caseId, runId(필수), evidence, options
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraAnalyzeRequest {

    private Long caseId;
    private UUID runId;
    private String mode;
    private String requestedBy;
    /** evidence snapshot (케이스 증거 스냅샷) */
    private JsonNode evidence;
    /** options: model, policyVersion 등 */
    private Map<String, Object> options;
}
