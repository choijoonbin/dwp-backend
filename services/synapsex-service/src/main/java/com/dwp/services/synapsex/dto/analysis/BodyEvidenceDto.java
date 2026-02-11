package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aura Phase2 분석 요청 규격: body_evidence — 특정 문서·항목 규정 준수 판단 시 사용.
 * 규격: { "body_evidence": { "doc_id": "BELNR_VALUE", "item_id": "BUZEI_VALUE" } }
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
}
