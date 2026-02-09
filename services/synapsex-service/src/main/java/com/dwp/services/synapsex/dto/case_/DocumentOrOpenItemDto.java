package com.dwp.services.synapsex.dto.case_;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CaseDetailDto 내 documentOrOpenItem 필드용 DTO.
 * 별도 클래스로 분리 (Lombok + inner class 시 ClassNotFoundException 방지)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentOrOpenItemDto {

    private String type;  // DOCUMENT | OPEN_ITEM
    private String docKey;
    private Object headerSummary;
    /** Phase A: DocumentLineItemDto 확장 (금액 외 hkont, bschl, sgtxt 등) */
    private List<DocumentLineItemDto> items;
    /** 라인 수 (프론트 "라인 항목(n)" 표기용) */
    private Integer lineCount;
    /** P0-3: 금액 (fi_doc_item wrbtr 합계 또는 fi_open_item open_amount) */
    private java.math.BigDecimal amount;
    /** P0-3: 통화 */
    private String currency;
}
