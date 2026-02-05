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
    private List<Object> items;
    /** P0-3: 금액 (fi_doc_item wrbtr 합계 또는 fi_open_item open_amount) */
    private java.math.BigDecimal amount;
    /** P0-3: 통화 */
    private String currency;
}
