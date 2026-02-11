package com.dwp.services.synapsex.service.workbench;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.client.AuthServerMenuClient;
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.workbench.ActionLinkDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchAnalysisResultDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchCaseDetailResponseDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchNavigationDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchSettingMenuDto;
import com.dwp.services.synapsex.dto.workbench.CaseActionHistoryItemDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchTimelineItemDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchTimelineMetadataDto;
import com.dwp.services.synapsex.entity.AgentActivityLog;
import com.dwp.services.synapsex.entity.AgentCaseActionHistory;
import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import com.dwp.services.synapsex.repository.AgentActivityLogRepository;
import com.dwp.services.synapsex.repository.AgentCaseActionHistoryRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisResultRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Workbench Aggregator: 한 번의 호출로 AgentCase + CaseAnalysisResult + AgentActivityLog 조합.
 * 모든 조회에 tenant_id 격리 적용. 타임라인은 occurred_at DESC, 기본 최근 50건.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchQueryService {

    private static final String RESOURCE_TYPE_AGENT_CASE = "AGENT_CASE";
    /** 대량 로그 대비 타임라인 기본 최대 건수 */
    private static final int DEFAULT_TIMELINE_LIMIT = 50;

    /** 워크벤치에서 바로 점프할 관련 설정 메뉴 키 (규정·정책·가드레일·사전·거버넌스) */
    private static final String WORKBENCH_RELATED_MENU_KEYS = "menu.knowledge-policy.rag,menu.knowledge-policy.policies,menu.knowledge-policy.guardrails,menu.knowledge-policy.dictionary,menu.governance-config.governance";

    private final CaseQueryService caseQueryService;
    private final CaseAnalysisRunRepository caseAnalysisRunRepository;
    private final CaseAnalysisResultRepository caseAnalysisResultRepository;
    private final AgentActivityLogRepository agentActivityLogRepository;
    private final AgentCaseActionHistoryRepository agentCaseActionHistoryRepository;
    private final AuthServerMenuClient authServerMenuClient;

    /**
     * 케이스 상세 + 최신 분석 결과 + 타임라인(agent_activity_log, occurred_at DESC, 최근 50건)을 한 번에 반환.
     * tenant_id 격리: case 조회 시 미존재면 ENTITY_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public WorkbenchCaseDetailResponseDto getCaseDetailWithTimeline(Long tenantId, Long caseId) {
        CaseDetailDto caseDetail = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));

        WorkbenchAnalysisResultDto latestAnalysis = findLatestAnalysis(tenantId, caseId);
        List<WorkbenchTimelineItemDto> timeline = findTimelineForCase(tenantId, caseId, DEFAULT_TIMELINE_LIMIT);
        List<ActionLinkDto> actionLinks = buildActionLinksForCase(caseId);

        return WorkbenchCaseDetailResponseDto.builder()
                .case_(caseDetail)
                .latestAnalysis(latestAnalysis)
                .timeline(timeline)
                .actionLinks(actionLinks)
                .build();
    }

    /**
     * 케이스와 연관된 지식(RAG)·정책 메뉴로 바로 이동할 수 있는 action_links 구성.
     * deepLink는 sys_menus.menu_path와 동일 (/synapse/rag, /synapse/policies). queryParams로 caseId 전달 시 FE에서 컨텍스트 강조 가능.
     */
    private List<ActionLinkDto> buildActionLinksForCase(Long caseId) {
        String q = (caseId != null) ? "caseId=" + caseId : null;
        return List.of(
                ActionLinkDto.builder()
                        .label("규정·문서 라이브러리")
                        .deepLink("/synapse/rag")
                        .type("RAG")
                        .queryParams(q)
                        .build(),
                ActionLinkDto.builder()
                        .label("정책 프로파일")
                        .deepLink("/synapse/policies")
                        .type("POLICY")
                        .queryParams(q)
                        .build()
        );
    }

    /**
     * 워크벤치 진입 시 관련 설정 메뉴 목록(deepLink 포함) 반환.
     * auth-server /auth/menus/entries 호출 — 사용자 VIEW 권한이 있는 메뉴만 포함.
     */
    @Transactional(readOnly = true)
    public WorkbenchNavigationDto getNavigation(Long tenantId) {
        try {
            ApiResponse<List<WorkbenchSettingMenuDto>> resp = authServerMenuClient.getMenuEntries(tenantId, WORKBENCH_RELATED_MENU_KEYS);
            List<WorkbenchSettingMenuDto> list = (resp != null && resp.getData() != null) ? resp.getData() : List.of();
            return WorkbenchNavigationDto.builder()
                    .relatedSettingsMenus(list)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load workbench navigation from auth: {}", e.getMessage());
            return WorkbenchNavigationDto.builder()
                    .relatedSettingsMenus(List.of())
                    .build();
        }
    }

    /** tenant_id + case_id로 최신 run 1건의 case_analysis_result 조회 */
    private WorkbenchAnalysisResultDto findLatestAnalysis(Long tenantId, Long caseId) {
        List<CaseAnalysisRun> runs = caseAnalysisRunRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        if (runs.isEmpty()) {
            return null;
        }
        UUID runId = runs.get(0).getRunId();
        Optional<CaseAnalysisResult> resultOpt = caseAnalysisResultRepository.findByRunId(runId);
        if (resultOpt.isEmpty()) {
            return null;
        }
        CaseAnalysisResult r = resultOpt.get();
        return WorkbenchAnalysisResultDto.builder()
                .runId(r.getRunId())
                .score(r.getScore())
                .severity(r.getSeverity())
                .reasonText(r.getReasonText())
                .confidenceJson(r.getConfidenceJson())
                .evidenceJson(r.getEvidenceJson())
                .similarJson(r.getSimilarJson())
                .ragRefsJson(r.getRagRefsJson())
                .createdAt(r.getCreatedAt())
                .build();
    }

    /**
     * agent_activity_log 조회: tenant_id + resource_type + resource_id 격리, occurred_at DESC, 최대 limit건.
     */
    private List<WorkbenchTimelineItemDto> findTimelineForCase(Long tenantId, Long caseId, int limit) {
        PageRequest pageable = PageRequest.of(0, limit);
        List<AgentActivityLog> logs = agentActivityLogRepository
                .findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtDesc(
                        tenantId, RESOURCE_TYPE_AGENT_CASE, String.valueOf(caseId), pageable);

        List<WorkbenchTimelineItemDto> list = new ArrayList<>(logs.size());
        for (AgentActivityLog log : logs) {
            WorkbenchTimelineMetadataDto metadata = mapMetadata(log.getMetadataJson());
            list.add(WorkbenchTimelineItemDto.builder()
                    .activityId(log.getActivityId())
                    .occurredAt(log.getOccurredAt())
                    .stage(log.getStage())
                    .eventType(log.getEventType())
                    .resourceType(log.getResourceType())
                    .resourceId(log.getResourceId())
                    .actorAgentId(log.getActorAgentId())
                    .actorUserId(log.getActorUserId())
                    .actorDisplayName(log.getActorDisplayName())
                    .metadata(metadata)
                    .metadataJson(log.getMetadataJson())
                    .build());
        }
        return list;
    }

    /**
     * GET /api/synapse/workbench/cases/{caseId}/history — agent_case_action_history 조회 (action_at DESC).
     */
    @Transactional(readOnly = true)
    public List<CaseActionHistoryItemDto> getCaseActionHistory(Long tenantId, Long caseId, int limit) {
        caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        PageRequest pageable = PageRequest.of(0, Math.min(200, Math.max(1, limit)));
        List<AgentCaseActionHistory> list = agentCaseActionHistoryRepository
                .findByTenantIdAndCaseIdOrderByActionAtDesc(tenantId, caseId, pageable).getContent();
        return list.stream()
                .map(h -> CaseActionHistoryItemDto.builder()
                        .id(h.getId())
                        .caseId(h.getCaseId())
                        .actionType(h.getActionType())
                        .actorId(h.getActorId())
                        .commentText(h.getCommentText())
                        .actionAt(h.getActionAt())
                        .metadataJson(h.getMetadataJson())
                        .createdAt(h.getCreatedAt())
                        .build())
                .toList();
    }

    /** Aura 표준: metadata_json → { title, reasoning, evidence, status } */
    private static WorkbenchTimelineMetadataDto mapMetadata(Map<String, Object> metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return null;
        }
        Map<String, Object> extra = new HashMap<>(metadataJson);
        String title = (String) extra.remove("title");
        String reasoning = (String) extra.remove("reasoning");
        Object evidence = extra.remove("evidence");
        String status = (String) extra.remove("status");
        return WorkbenchTimelineMetadataDto.builder()
                .title(title)
                .reasoning(reasoning)
                .evidence(evidence)
                .status(status)
                .extra(extra.isEmpty() ? null : extra)
                .build();
    }
}
