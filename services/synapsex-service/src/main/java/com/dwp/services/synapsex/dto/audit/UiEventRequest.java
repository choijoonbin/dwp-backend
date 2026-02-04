package com.dwp.services.synapsex.dto.audit;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * UI 이벤트 감사 요청 (POST /api/synapse/audit/ui-events)
 * event_category=UI, evidence_json에 query/metadata 저장.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiEventRequest {

    /** DASHBOARD_DRILLDOWN | DASHBOARD_REVIEW_CASE | DASHBOARD_VIEW_AUDIT | FILTER_APPLY */
    @NotBlank(message = "eventType은 필수입니다.")
    private String eventType;

    /** 대상 라우트: /cases, /anomalies, /actions, /audit 등 */
    private String targetRoute;

    /** 쿼리 파라미터 (JSON) */
    private Map<String, Object> query;

    /** 위젯/행 ID, riskType, caseKey 등 (JSON) */
    private Map<String, Object> metadata;
}
