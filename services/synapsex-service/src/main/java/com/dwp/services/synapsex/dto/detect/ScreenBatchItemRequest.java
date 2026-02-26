package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /** payload 스키마 버전 */
    private String schemaVersion;
    /** 테넌트 식별자 */
    private Long tenantId;
    /** 전표 키 (bukrs-belnr-gjahr) */
    private String voucherKey;
    /** 회사코드 */
    private String bukrs;
    /** 전표번호 */
    private String belnr;
    /** 회계연도 */
    private String gjahr;
    /** 라인번호 */
    private String buzei;
    /** 전표 합계금액 */
    private BigDecimal amount;
    /** 통화 */
    private String currency;
    /** 증빙일자/시간 (ISO-8601 권장, 예: 2024-01-15T14:30:00) */
    private String occurredAt;
    /** 타임존 */
    private String timezone;
    /** 경비유형 (예: blart 문서유형) */
    private String expenseType;
    /** 경비유형명(사람이 읽는 표시명) */
    private String expenseTypeName;
    /** 가맹점명 */
    private String merchantName;
    /** 가맹점 식별자(현재 미연계 시 null) */
    private String merchantId;
    /** 케이스 ID (신규 스크리닝 시 null) */
    private Long caseId;
    /** 규정 v2.0: 근무/휴가 (WORK, LEAVE). Aura metadata 전달용. */
    private String hrStatus;
    /** 정규화 전 원본 근태값 */
    private String hrStatusRaw;
    /** 규정 v2.0: 업종 코드 또는 라벨 (예: RESTAURANT, GOLF). */
    private String mccCode;
    /** 정규화 전 원본 업종코드 */
    private String mccCodeRaw;
    /** 업종명 */
    private String mccName;
    /** MCC 위험 카테고리(LOW|MEDIUM|HIGH) */
    private String mccRiskCategory;
    /** 규정 v2.0: 한도초과 여부. */
    private Boolean budgetExceeded;
    /** 한도초과 원본 플래그(Y/N) */
    private String budgetExceededFlag;
    /** 규정 v3.1: 휴일 여부 (OFF=true, VACATION=false, 그 외 규칙은 BE 문서 기준). */
    private Boolean isHoliday;
    /** 휴일 유형 */
    private String holidayType;
    /** 주말 허용 여부 (MCC 정책) */
    private String isWeekendAllowed;
    /** 관련 조항 힌트 */
    private List<String> relatedArticleHint;
    /** 관련 조항은 힌트 전용(HINT_ONLY). 최종 근거는 RAG citation 사용. */
    private String relatedArticleHintUsage;
    /** 소스 시스템 */
    private String sourceSystem;
    /** 소스 레코드 타임스탬프 */
    private String sourceTimestamp;
    /** 정규화/매핑 플래그 */
    private Map<String, Object> normalizationFlags;
    /** 데이터 품질 보조 정보 */
    private Map<String, Object> dataQuality;
}
