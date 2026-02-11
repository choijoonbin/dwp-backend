package com.dwp.services.synapsex.service.analysis;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.analysis.CaseActionProposalDto;
import com.dwp.services.synapsex.dto.analysis.CaseAnalysisDto;
import com.dwp.services.synapsex.dto.analysis.AnalysisRunStatusDto;
import com.dwp.services.synapsex.entity.CaseActionProposal;
import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.CaseActionProposalRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisResultRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 케이스 분석·실행·제안 조회 전용 (Query). Command는 CaseAnalysisService.
 */
@Service
@RequiredArgsConstructor
public class CaseAnalysisQueryService {

    private final CaseAnalysisRunRepository runRepository;
    private final CaseAnalysisResultRepository resultRepository;
    private final CaseActionProposalRepository proposalRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final ObjectMapper objectMapper;
    private final DrillDownCodeResolver drillDownCodeResolver;

    @Transactional(readOnly = true)
    public Object getAnalysisRuns(Long tenantId, Long caseId, boolean latest) {
        agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        List<CaseAnalysisRun> runs = runRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        if (runs.isEmpty()) {
            return latest ? java.util.Collections.singletonMap("runId", null) : List.of();
        }
        if (latest) {
            return Map.of("runId", runs.get(0).getRunId());
        }
        return runs.stream()
                .map(r -> Map.<String, Object>of(
                        "runId", r.getRunId(),
                        "status", r.getStatus(),
                        "startedAt", r.getStartedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AnalysisRunStatusDto getRunStatus(Long tenantId, UUID runId) {
        CaseAnalysisRun run = runRepository.findByRunIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "분석 실행을 찾을 수 없습니다."));
        return AnalysisRunStatusDto.builder()
                .runId(run.getRunId())
                .caseId(run.getCaseId())
                .status(run.getStatus())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .error(run.getErrorMessage())
                .build();
    }

    @Transactional(readOnly = true)
    public CaseAnalysisDto getCaseAnalysis(Long tenantId, Long caseId, UUID runId) {
        agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));

        if (runId != null) {
            Optional<CaseAnalysisRun> runOpt = runRepository.findByRunIdAndTenantId(runId, tenantId);
            if (runOpt.isPresent() && runOpt.get().getCaseId().equals(caseId)) {
                CaseAnalysisRun run = runOpt.get();
                Optional<CaseAnalysisResult> resOpt = resultRepository.findByRunId(runId);
                if (resOpt.isPresent()) {
                    return toCaseAnalysisDto(resOpt.get(), run, runId);
                }
                return CaseAnalysisDto.builder()
                        .empty(true)
                        .reason("해당 runId의 분석 결과가 아직 없습니다.")
                        .build();
            }
            return CaseAnalysisDto.builder()
                    .empty(true)
                    .reason("해당 runId를 찾을 수 없습니다.")
                    .build();
        }

        List<CaseAnalysisRun> runs = runRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        for (CaseAnalysisRun run : runs) {
            if (CaseAnalysisRun.STATUS_COMPLETED.equals(run.getStatus())) {
                Optional<CaseAnalysisResult> resOpt = resultRepository.findByRunId(run.getRunId());
                if (resOpt.isPresent()) {
                    return toCaseAnalysisDto(resOpt.get(), run, run.getRunId());
                }
            }
        }
        return CaseAnalysisDto.builder()
                .empty(true)
                .reason("아직 분석 결과가 없습니다(Phase2-1: BE demo stream 단계).")
                .build();
    }

    @Transactional(readOnly = true)
    public List<CaseActionProposalDto> getActionProposals(Long tenantId, Long caseId, UUID runId) {
        agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));

        List<CaseActionProposal> proposals = runId != null
                ? proposalRepository.findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(tenantId, caseId, runId)
                : proposalRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId);
        return proposals.stream().map(this::toProposalDto).collect(Collectors.toList());
    }

    /** Result + Run + runIdFilter로 CaseAnalysisDto 생성 (내부·동일 패키지에서 사용) */
    public CaseAnalysisDto toCaseAnalysisDto(CaseAnalysisResult r, CaseAnalysisRun run, UUID runIdFilter) {
        List<CaseActionProposal> proposalList;
        if (runIdFilter != null) {
            proposalList = proposalRepository.findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(run.getTenantId(), run.getCaseId(), runIdFilter);
        } else {
            proposalList = proposalRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(run.getTenantId(), run.getCaseId()).stream()
                    .filter(proposal -> run.getRunId().equals(proposal.getRunId()))
                    .toList();
        }
        List<CaseActionProposalDto> proposals = proposalList.stream().map(this::toProposalDto).collect(Collectors.toList());

        List<Map<String, Object>> evidence = jsonToList(r.getEvidenceJson());
        List<Map<String, Object>> similar = jsonToList(r.getSimilarJson());
        List<Map<String, Object>> ragRefs = jsonToList(r.getRagRefsJson());

        return CaseAnalysisDto.builder()
                .runId(r.getRunId())
                .score(r.getScore())
                .severity(r.getSeverity())
                .reasonText(r.getReasonText())
                .confidenceBreakdown(r.getConfidenceJson())
                .evidence(evidence)
                .similarCases(similar)
                .ragRefs(ragRefs)
                .proposals(proposals)
                .build();
    }

    public CaseActionProposalDto toProposalDto(CaseActionProposal proposal) {
        return CaseActionProposalDto.builder()
                .proposalId(proposal.getProposalId())
                .runId(proposal.getRunId())
                .type(proposal.getType())
                .typeName(drillDownCodeResolver.getCodeName(DrillDownCodeResolver.GROUP_ACTION_TYPE, proposal.getType()))
                .status(proposal.getStatus())
                .riskLevel(proposal.getRiskLevel())
                .rationale(proposal.getRationale())
                .payload(proposal.getPayloadJson())
                .createdAt(proposal.getCreatedAt())
                .updatedAt(proposal.getUpdatedAt())
                .requiresApproval(proposal.getRequiresApproval())
                .fingerprint(proposal.getDedupKey())
                .decidedBy(proposal.getDecidedBy())
                .decidedAt(proposal.getDecidedAt())
                .decisionComment(proposal.getDecisionComment())
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> jsonToList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        node.forEach(n -> list.add(objectMapper.convertValue(n, Map.class)));
        return list;
    }
}
