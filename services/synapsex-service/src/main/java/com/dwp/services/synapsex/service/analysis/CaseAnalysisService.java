package com.dwp.services.synapsex.service.analysis;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.dto.analysis.*;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.CaseActionProposal;
import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.CaseActionProposalRepository;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.repository.CaseAnalysisResultRepository;
import com.dwp.services.synapsex.util.ProposalDedupKeyUtil;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase2: 케이스 분석 실행, 결과, 액션 제안
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseAnalysisService {

    private final CaseAnalysisRunRepository runRepository;
    private final CaseAnalysisResultRepository resultRepository;
    private final CaseActionProposalRepository proposalRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AuraCaseTabClient auraCaseTabClient;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final DrillDownCodeResolver drillDownCodeResolver;

    @Value("${synapse.demo-mode:false}")
    private boolean demoMode;

    @Transactional
    public AnalysisRunTriggerResponse triggerAnalysis(Long tenantId, Long caseId,
                                                      AnalysisRunTriggerRequest request,
                                                      Long userId, String authorization) {
        AgentCase agentCase = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));

        String mode = request != null && request.getMode() != null ? request.getMode() : "LIVE";
        String requestedBy = request != null && request.getRequestedBy() != null ? request.getRequestedBy() : "HUMAN";

        CaseAnalysisRun run = CaseAnalysisRun.builder()
                .tenantId(tenantId)
                .caseId(caseId)
                .status(CaseAnalysisRun.STATUS_STARTED)
                .mode(mode)
                .requestedBy(requestedBy)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        UUID runId = run.getRunId();
        final String beStreamUrl = "/api/synapse/analysis-runs/" + runId + "/stream";
        final String auraStreamUrl = "/api/aura/analysis-runs/" + runId + "/stream";
        String streamUrl = demoMode ? beStreamUrl : auraStreamUrl;

        if (demoMode) {
            completeDemoRun(run);
            return AnalysisRunTriggerResponse.builder()
                    .runId(runId)
                    .status(CaseAnalysisRun.STATUS_STARTED)
                    .streamUrl(streamUrl)
                    .startedAt(run.getStartedAt())
                    .build();
        }

        try {
            JsonNode evidenceSnapshot = (request != null && request.getEvidenceSnapshot() != null)
                    ? request.getEvidenceSnapshot()
                    : buildEvidenceSnapshot(agentCase);
            AuraAnalyzeRequest auraReq = AuraAnalyzeRequest.builder()
                    .caseId(caseId)
                    .runId(runId)
                    .mode(mode)
                    .requestedBy(requestedBy)
                    .evidence(evidenceSnapshot)
                    .options(request != null ? request.getOptions() : null)
                    .build();
            AuraAnalyzeResponse auraRes = auraCaseTabClient.triggerAnalyze(caseId, tenantId, authorization, userId, auraReq);
            if (auraRes != null) {
                if ("disabled".equals(auraRes.getStatus())) {
                    failRunWithMessage(run, auraRes.getMessage());
                    streamUrl = beStreamUrl;
                } else if (auraRes.getStreamUrl() != null) {
                    streamUrl = auraRes.getStreamUrl();
                }
            }
        } catch (FeignException e) {
            // 202 Accepted: Aura가 비동기 수락 → run 유지, 콜백 대기. 실패로 처리하지 않음
            if (e.status() == 202) {
                log.info("Aura analyze trigger accepted (202), run created: runId={}", runId);
            } else {
                log.warn("Aura analyze trigger failed, run created: runId={} status={}", runId, e.status());
                failRunWithMessage(run, "Aura analyze trigger failed: " + e.status());
                streamUrl = beStreamUrl;
            }
        }

        logAudit(tenantId, caseId, runId, null,
                run.getStatus().equals(CaseAnalysisRun.STATUS_FAILED) ? AuditEventConstants.TYPE_ANALYSIS_RUN_FAILED : AuditEventConstants.TYPE_ANALYSIS_RUN_STARTED,
                "ANALYSIS_RUN", runId.toString(), Map.of("status", run.getStatus()));

        return AnalysisRunTriggerResponse.builder()
                .runId(runId)
                .status(run.getStatus())
                .streamUrl(streamUrl)
                .startedAt(run.getStartedAt())
                .build();
    }

    private void failRunWithMessage(CaseAnalysisRun run, String message) {
        String msg = message != null && !message.isBlank() ? message : "Analysis disabled or failed";
        run.setStatus(CaseAnalysisRun.STATUS_FAILED);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(msg);
        runRepository.save(run);
    }

    /**
     * 케이스 evidence snapshot 생성 — Aura 트리거 요청용.
     * agent_case.evidence_json + rag_refs_json을 합쳐 전달.
     */
    private JsonNode buildEvidenceSnapshot(AgentCase agentCase) {
        JsonNode evidence = agentCase.getEvidenceJson();
        JsonNode ragRefs = agentCase.getRagRefsJson();
        if (evidence == null && ragRefs == null) {
            return null;
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        if (evidence != null) snapshot.set("evidence", evidence);
        if (ragRefs != null) snapshot.set("ragRefs", ragRefs);
        return snapshot;
    }

    private void completeDemoRun(CaseAnalysisRun run) {
        run.setStatus(CaseAnalysisRun.STATUS_COMPLETED);
        run.setFinishedAt(Instant.now());
        runRepository.save(run);

        CaseAnalysisResult result = CaseAnalysisResult.builder()
                .runId(run.getRunId())
                .score(BigDecimal.valueOf(72))
                .severity("MEDIUM")
                .reasonText("정책 위반 가능성이 있는 전표 조합입니다.")
                .confidenceJson(objectMapper.createObjectNode().put("overall", 0.72))
                .evidenceJson(objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("key", "중복 지급 의심"))
                        .add(objectMapper.createObjectNode().put("key", "벤더 계좌 변경 직후 지급")))
                .similarJson(objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("caseId", 99901).put("score", 0.82))
                        .add(objectMapper.createObjectNode().put("caseId", 99902).put("score", 0.76)))
                .ragRefsJson(objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("title", "지급 통제 정책").put("relevance", 0.91)))
                .createdAt(Instant.now())
                .build();
        resultRepository.save(result);

        String dedupKey = ProposalDedupKeyUtil.compute("HOLD_PAYMENT", objectMapper.createObjectNode(), "계좌 변경 72시간 룰 위반 가능");
        if (!proposalRepository.existsByCaseIdAndRunIdAndDedupKey(run.getCaseId(), run.getRunId(), dedupKey)) {
            CaseActionProposal proposal = CaseActionProposal.builder()
                    .tenantId(run.getTenantId())
                    .caseId(run.getCaseId())
                    .runId(run.getRunId())
                    .type("HOLD_PAYMENT")
                    .dedupKey(dedupKey)
                    .status(CaseActionProposal.STATUS_PROPOSED)
                    .riskLevel("MEDIUM")
                    .rationale("계좌 변경 72시간 룰 위반 가능")
                    .payloadJson(objectMapper.createObjectNode())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            proposal = proposalRepository.save(proposal);
            logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), proposal.getProposalId(),
                    AuditEventConstants.TYPE_ACTION_PROPOSED, "ACTION_PROPOSAL", proposal.getProposalId().toString(),
                    Map.of("type", "HOLD_PAYMENT", "status", "PROPOSED"));
        }

        logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), null,
                AuditEventConstants.TYPE_ANALYSIS_RUN_COMPLETED, "ANALYSIS_RUN", run.getRunId().toString(),
                Map.of("status", "COMPLETED", "score", 72));
    }

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

    @Transactional
    public void handleAuraCallback(AuraCallbackPayload payload) {
        UUID runId = payload.getRunId();
        if (runId == null) {
            log.warn("Aura callback missing runId");
            return;
        }
        CaseAnalysisRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Aura callback runId not found: {}", runId);
            return;
        }

        String status = payload.getStatus();
        run.setStatus("COMPLETED".equals(status) ? CaseAnalysisRun.STATUS_COMPLETED : CaseAnalysisRun.STATUS_FAILED);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage("FAILED".equals(status) ? "Aura callback status: FAILED" : null);
        if (payload.getAuraTraceId() != null) run.setAuraTraceId(payload.getAuraTraceId());
        runRepository.save(run);

        if ("COMPLETED".equals(status) && payload.getFinalResult() != null) {
            saveResultAndProposals(run, payload.getFinalResult());
        }

        logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), null,
                "COMPLETED".equals(status) ? AuditEventConstants.TYPE_ANALYSIS_RUN_COMPLETED : AuditEventConstants.TYPE_ANALYSIS_RUN_FAILED,
                "ANALYSIS_RUN", runId.toString(), Map.of("status", status));
    }

    private void saveResultAndProposals(CaseAnalysisRun run, AuraCallbackPayload.FinalResult fr) {
        JsonNode confidenceJson = fr.getConfidence();

        ArrayNode evidenceJson = objectMapper.createArrayNode();
        if (fr.getEvidence() != null) fr.getEvidence().forEach(m -> evidenceJson.add(objectMapper.valueToTree(m)));

        ArrayNode similarJson = objectMapper.createArrayNode();
        if (fr.getSimilar() != null) fr.getSimilar().forEach(m -> similarJson.add(objectMapper.valueToTree(m)));

        ArrayNode ragRefsJson = objectMapper.createArrayNode();
        if (fr.getRagRefs() != null) fr.getRagRefs().forEach(m -> ragRefsJson.add(objectMapper.valueToTree(m)));

        CaseAnalysisResult result = CaseAnalysisResult.builder()
                .runId(run.getRunId())
                .score(fr.getScore() != null ? BigDecimal.valueOf(fr.getScore()) : null)
                .severity(fr.getSeverity())
                .reasonText(fr.getReasonText())
                .confidenceJson(confidenceJson)
                .evidenceJson(evidenceJson.isEmpty() ? null : evidenceJson)
                .similarJson(similarJson.isEmpty() ? null : similarJson)
                .ragRefsJson(ragRefsJson.isEmpty() ? null : ragRefsJson)
                .createdAt(Instant.now())
                .build();
        resultRepository.save(result);

        if (fr.getProposals() != null) {
            for (AuraCallbackPayload.ProposalItem p : fr.getProposals()) {
                String type = p.getType() != null ? p.getType() : "UNKNOWN";
                String dedupKey = ProposalDedupKeyUtil.compute(type, p.getPayload(), p.getRationale());
                if (proposalRepository.existsByCaseIdAndRunIdAndDedupKey(run.getCaseId(), run.getRunId(), dedupKey)) {
                    continue; // 중복 제안 스킵 (BE dedup)
                }
                Instant createdAt = p.getCreatedAt() != null ? p.getCreatedAt() : Instant.now();
                CaseActionProposal prop = CaseActionProposal.builder()
                        .tenantId(run.getTenantId())
                        .caseId(run.getCaseId())
                        .runId(run.getRunId())
                        .type(type)
                        .dedupKey(dedupKey)
                        .status(CaseActionProposal.STATUS_PROPOSED)
                        .riskLevel(p.getRiskLevel())
                        .rationale(p.getRationale())
                        .payloadJson(p.getPayload())
                        .requiresApproval(p.getRequiresApproval())
                        .createdAt(createdAt)
                        .updatedAt(Instant.now())
                        .build();
                prop = proposalRepository.save(prop);
                logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), prop.getProposalId(),
                        AuditEventConstants.TYPE_ACTION_PROPOSED, "ACTION_PROPOSAL", prop.getProposalId().toString(),
                        Map.of("type", prop.getType(), "status", "PROPOSED"));
            }
        }
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

    private CaseAnalysisDto toCaseAnalysisDto(CaseAnalysisResult r, CaseAnalysisRun run, UUID runIdFilter) {
        List<CaseActionProposal> proposalList;
        if (runIdFilter != null) {
            proposalList = proposalRepository.findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(run.getTenantId(), run.getCaseId(), runIdFilter);
        } else {
            proposalList = proposalRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(run.getTenantId(), run.getCaseId()).stream()
                    .filter(proposal -> run.getRunId().equals(proposal.getRunId()))
                    .toList();
        }
        List<CaseActionProposalDto> proposals = proposalList.stream()
                .map(proposal -> CaseActionProposalDto.builder()
                        .proposalId(proposal.getProposalId())
                        .runId(proposal.getRunId())
                        .type(proposal.getType())
                        .typeName(drillDownCodeResolver.getCodeName(DrillDownCodeResolver.GROUP_ACTION_TYPE, proposal.getType()))
                        .status(proposal.getStatus())
                        .riskLevel(proposal.getRiskLevel())
                        .rationale(proposal.getRationale())
                        .payload(proposal.getPayloadJson())
                        .createdAt(proposal.getCreatedAt())
                        .requiresApproval(proposal.getRequiresApproval())
                        .build())
                .collect(Collectors.toList());

        List<Map<String, Object>> evidence = jsonToList(r.getEvidenceJson());
        List<Map<String, Object>> similar = jsonToList(r.getSimilarJson());
        List<Map<String, Object>> ragRefs = jsonToList(r.getRagRefsJson());

        return CaseAnalysisDto.builder()
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jsonToList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        node.forEach(n -> list.add(objectMapper.convertValue(n, Map.class)));
        return list;
    }

    @Transactional(readOnly = true)
    public List<CaseActionProposalDto> getActionProposals(Long tenantId, Long caseId, UUID runId) {
        agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));

        List<CaseActionProposal> proposals = runId != null
                ? proposalRepository.findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(tenantId, caseId, runId)
                : proposalRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId);
        return proposals.stream()
                .map(proposal -> CaseActionProposalDto.builder()
                        .proposalId(proposal.getProposalId())
                        .runId(proposal.getRunId())
                        .type(proposal.getType())
                        .typeName(drillDownCodeResolver.getCodeName(DrillDownCodeResolver.GROUP_ACTION_TYPE, proposal.getType()))
                        .status(proposal.getStatus())
                        .riskLevel(proposal.getRiskLevel())
                        .rationale(proposal.getRationale())
                        .payload(proposal.getPayloadJson())
                        .createdAt(proposal.getCreatedAt())
                        .requiresApproval(proposal.getRequiresApproval())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void approveProposal(Long tenantId, UUID proposalId, Long userId) {
        CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(proposalId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
        proposal.setStatus(CaseActionProposal.STATUS_APPROVED);
        proposal.setUpdatedAt(Instant.now());
        proposalRepository.save(proposal);
        logAudit(tenantId, proposal.getCaseId(), proposal.getRunId(), proposalId, AuditEventConstants.TYPE_ACTION_APPROVED,
                "ACTION_PROPOSAL", proposalId.toString(), Map.of("status", "APPROVED", "actorUserId", userId));
    }

    @Transactional
    public void rejectProposal(Long tenantId, UUID proposalId, Long userId) {
        CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(proposalId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
        proposal.setStatus(CaseActionProposal.STATUS_REJECTED);
        proposal.setUpdatedAt(Instant.now());
        proposalRepository.save(proposal);
        logAudit(tenantId, proposal.getCaseId(), proposal.getRunId(), proposalId, AuditEventConstants.TYPE_ACTION_REJECTED,
                "ACTION_PROPOSAL", proposalId.toString(), Map.of("status", "REJECTED", "actorUserId", userId));
    }

    private void logAudit(Long tenantId, Long caseId, UUID runId, UUID proposalId, String eventType,
                          String resourceType, String resourceId, Map<String, Object> afterJson) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "CASE_ANALYSIS");
        if (caseId != null) tags.put("caseId", caseId);
        if (runId != null) tags.put("runId", runId.toString());
        if (proposalId != null) tags.put("proposalId", proposalId.toString());
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_CASE, eventType,
                resourceType, resourceId,
                AuditEventConstants.ACTOR_SYSTEM, null, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, afterJson, null, null, tags, null, null, null, null, null);
    }
}
