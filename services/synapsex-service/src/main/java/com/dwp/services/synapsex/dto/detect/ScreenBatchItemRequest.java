package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Aura 배치 스크리닝 요청 1건 — Flatten된 필드만 전달.
 * POST /aura/detect/screen-batch 본문은 본 DTO의 순수 JSON 배열 [...] 형태.
 *
 * 매핑: amount(전표 합계금액), occurredAt(증빙일자/시간), expenseType(경비유형), merchantName(가맹점명), caseId(케이스 ID, 선택)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenBatchItemRequest {

    /** 전표 합계금액 */
    private BigDecimal amount;
    /** 증빙일자/시간 (ISO-8601 권장, 예: 2024-01-15T14:30:00) */
    private String occurredAt;
    /** 경비유형 (예: blart 문서유형) */
    private String expenseType;
    /** 가맹점명 */
    private String merchantName;
    /** 케이스 ID (신규 스크리닝 시 null) */
    private Long caseId;
    /** 규정 v2.0: 근무/휴가 (WORK, LEAVE). Aura metadata 전달용. */
    private String hrStatus;
    /** 규정 v2.0: 업종 코드 또는 라벨 (예: RESTAURANT, GOLF). */
    private String mccCode;
    /** 규정 v2.0: 한도초과 여부. */
    private Boolean budgetExceeded;
}
