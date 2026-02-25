package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aura Phase2 분석 요청 규격: body_evidence — 특정 문서·항목 규정 준수 판단 시 사용.
 * 규격: { "body_evidence": { "doc_id", "item_id", "case_type", "screening_reason_text" } }
 * case_type/reasonText 포함 시 Aura가 스크리닝 결과를 반영해 분석(risk_type fallback 방지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BodyEvidenceDto {

    /** 문서 식별자 (전표: belnr 또는 docKey bukrs-belnr-gjahr) */
    @JsonProperty("doc_id")
    private String docId;
    /** 항목 식별자 (전표 라인: buzei) */
    @JsonProperty("item_id")
    private String itemId;
    /** 스크리닝 케이스 유형 (예: HOLIDAY_USAGE). Aura가 risk_type 대신 사용 */
    @JsonProperty("case_type")
    @JsonAlias("caseType")
    private String caseType;
    /** 스크리닝 사유(제9조 등). Aura가 분석 시 반영 */
    @JsonProperty("screening_reason_text")
    @JsonAlias("reasonText")
    private String reasonText;
}
