package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 케이스 상세에서 지식(RAG)·정책 등 관련 메뉴로 바로 이동하기 위한 링크.
 * GET /synapse/workbench/cases/{caseId} 응답의 action_links 에 포함.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionLinkDto {

    /** 표시 라벨 (예: 규정·문서 라이브러리, 정책 프로파일) */
    private String label;
    /** 프론트 라우트 경로 (deepLink, 예: /synapse/rag, /synapse/policies) */
    private String deepLink;
    /** 링크 유형: RAG, POLICY, GUARDRAIL 등 (FE 배지/아이콘용) */
    private String type;
    /** 선택: 케이스 컨텍스트 쿼리 (예: caseId=123 → FE에서 해당 케이스 관련 문서/정책 강조) */
    private String queryParams;
}
