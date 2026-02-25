package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Aura 스크리닝 API 응답: severity, score, reasonText, caseType
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetectScreenResponse {

    /** sys_codes SEVERITY (CRITICAL, HIGH, MEDIUM, LOW, INFO) */
    private String severity;
    /** 0~100 위험 점수 */
    private BigDecimal score;
    /** 판단 근거 요약 (Aura snake_case: reason_text) */
    @JsonProperty("reasonText")
    @JsonAlias("reason_text")
    private String reasonText;
    /** case_type (예: DOC_WINDOW). Aura snake_case: case_type */
    @JsonProperty("caseType")
    @JsonAlias("case_type")
    private String caseType;
}
