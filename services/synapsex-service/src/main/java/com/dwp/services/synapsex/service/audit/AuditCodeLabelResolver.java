package com.dwp.services.synapsex.service.audit;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 감사 이벤트 코드 → name(라벨) 매핑.
 * V37 AUDIT_* 코드와 동기화. 매핑 없으면 code 그대로 반환.
 */
@Component
public class AuditCodeLabelResolver {

    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("CASE", "케이스"),
            Map.entry("ACTION", "조치"),
            Map.entry("ADMIN", "관리"),
            Map.entry("AUDIT", "감사"),
            Map.entry("RUN", "실행"),
            Map.entry("UI", "UI"),
            Map.entry("DASHBOARD", "대시보드"),
            Map.entry("POLICY", "정책"),
            Map.entry("FEEDBACK", "피드백"),
            Map.entry("INTEGRATION", "통합")
    );

    private static final Map<String, String> EVENT_TYPE = Map.ofEntries(
            Map.entry("STATUS_CHANGE", "상태 변경"),
            Map.entry("CASE_VIEW_LIST", "케이스 목록 조회"),
            Map.entry("CASE_VIEW_DETAIL", "케이스 상세 조회"),
            Map.entry("CASE_ASSIGN", "케이스 할당"),
            Map.entry("CASE_COMMENT_CREATE", "코멘트 생성"),
            Map.entry("DOCUMENT_VIEW_LIST", "전표 목록 조회"),
            Map.entry("DOCUMENT_VIEW_DETAIL", "전표 상세 조회"),
            Map.entry("OPENITEM_VIEW_LIST", "미결제 목록 조회"),
            Map.entry("OPENITEM_VIEW_DETAIL", "미결제 상세 조회"),
            Map.entry("ACTION_VIEW_LIST", "조치 목록 조회"),
            Map.entry("ACTION_VIEW_DETAIL", "조치 상세 조회"),
            Map.entry("AUDIT_VIEW_LIST", "감사 목록 조회"),
            Map.entry("AUDIT_VIEW_DETAIL", "감사 상세 조회"),
            Map.entry("RUN_DETECT_STARTED", "배치 시작"),
            Map.entry("RUN_DETECT_COMPLETED", "배치 완료"),
            Map.entry("RUN_DETECT_FAILED", "배치 실패"),
            Map.entry("RUN_DETECT_MANUAL_TRIGGERED", "수동 트리거"),
            Map.entry("CASE_CREATED", "케이스 생성"),
            Map.entry("CASE_UPDATED", "케이스 갱신"),
            Map.entry("FILTER_APPLY", "필터 적용"),
            Map.entry("DASHBOARD_VIEWED", "대시보드 조회"),
            Map.entry("UPDATE", "수정"),
            Map.entry("BULK_UPDATE", "일괄 수정"),
            Map.entry("PROPOSE", "제안"),
            Map.entry("APPROVE", "승인"),
            Map.entry("REJECT", "거절"),
            Map.entry("EXECUTE", "실행"),
            Map.entry("FAILED", "실패")
    );

    private static final Map<String, String> OUTCOME = Map.ofEntries(
            Map.entry("SUCCESS", "성공"),
            Map.entry("FAILED", "실패"),
            Map.entry("DENIED", "거부"),
            Map.entry("NOOP", "무작위")
    );

    private static final Map<String, String> ACTOR_TYPE = Map.ofEntries(
            Map.entry("HUMAN", "사용자"),
            Map.entry("AGENT", "에이전트"),
            Map.entry("SYSTEM", "시스템")
    );

    private static final Map<String, String> SEVERITY = Map.ofEntries(
            Map.entry("INFO", "정보"),
            Map.entry("WARN", "경고"),
            Map.entry("HIGH", "높음"),
            Map.entry("CRITICAL", "치명")
    );

    private static final Map<String, String> RESOURCE_TYPE = Map.ofEntries(
            Map.entry("AGENT_CASE", "케이스"),
            Map.entry("AGENT_ACTION", "조치"),
            Map.entry("DETECT_RUN", "탐지 실행"),
            Map.entry("AUDIT_EVENT", "감사 이벤트"),
            Map.entry("ROUTE", "라우트"),
            Map.entry("DASHBOARD", "대시보드")
    );

    public String getEventCategoryName(String code) {
        return code != null ? CATEGORY.getOrDefault(code, code) : null;
    }

    public String getEventTypeName(String code) {
        return code != null ? EVENT_TYPE.getOrDefault(code, code) : null;
    }

    public String getOutcomeName(String code) {
        return code != null ? OUTCOME.getOrDefault(code, code) : null;
    }

    public String getActorTypeName(String code) {
        return code != null ? ACTOR_TYPE.getOrDefault(code, code) : null;
    }

    public String getSeverityName(String code) {
        return code != null ? SEVERITY.getOrDefault(code, code) : null;
    }

    public String getResourceTypeName(String code) {
        return code != null ? RESOURCE_TYPE.getOrDefault(code, code) : null;
    }
}
