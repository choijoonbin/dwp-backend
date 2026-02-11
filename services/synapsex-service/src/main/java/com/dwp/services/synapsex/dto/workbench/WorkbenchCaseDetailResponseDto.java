package com.dwp.services.synapsex.dto.workbench;

import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/v1/synapse/workbench/cases/{caseId} 응답.
 * 케이스 상세 + 최신 분석 결과 + 타임라인(agent_activity_log, occurred_at DESC) 통합.
 * action_links: 해당 케이스와 연관된 지식(RAG)·정책 메뉴로 바로 이동할 수 있는 deepLink 목록.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkbenchCaseDetailResponseDto {

    /** 케이스 마스터 상세 (3-panel 등) */
    private CaseDetailDto case_;
    /** 최신 case_analysis_result 1건, 없으면 null */
    private WorkbenchAnalysisResultDto latestAnalysis;
    /** agent_activity_log 기반 타임라인, occurred_at DESC, 기본 최근 50건 */
    private List<WorkbenchTimelineItemDto> timeline;
    /** 지식(RAG)·정책 등 관련 메뉴로 바로 이동용 링크 (label, deepLink, type, queryParams) */
    private List<ActionLinkDto> actionLinks;
}
