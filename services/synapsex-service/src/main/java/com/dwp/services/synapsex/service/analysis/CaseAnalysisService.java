package com.dwp.services.synapsex.service.analysis;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.dto.analysis.*;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.CaseActionExecution;
import com.dwp.services.synapsex.entity.CaseActionProposal;
import com.dwp.services.synapsex.entity.CaseAnalysisResult;
import com.dwp.services.synapsex.entity.CaseAnalysisRun;
import com.dwp.services.synapsex.dto.case_.DocumentOrOpenItemDto;
import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.CaseActionProposalRepository;
import com.dwp.services.synapsex.repository.FiOpenItemRepository;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.repository.CaseAnalysisResultRepository;
import com.dwp.services.synapsex.util.ProposalDedupKeyUtil;
import com.dwp.services.synapsex.repository.CaseActionExecutionRepository;
import com.dwp.services.synapsex.repository.CaseAnalysisRunRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.lineage.LineageQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
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

/**
 * Phase2: 케이스 분석 실행, 결과, 액션 제안
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseAnalysisService {

    private static final String RESOURCE_TYPE_ACTION_PROPOSAL = "ACTION_PROPOSAL";

    private final CaseAnalysisRunRepository runRepository;
    private final CaseAnalysisResultRepository resultRepository;
    private final CaseActionProposalRepository proposalRepository;
    private final CaseActionExecutionRepository executionRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final AuraCaseTabClient auraCaseTabClient;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final DrillDownCodeResolver drillDownCodeResolver;
    private final CaseQueryService caseQueryService;
    private final LineageQueryService lineageQueryService;
    private final CaseAnalysisQueryService caseAnalysisQueryService;

    @Value("${synapse.demo-mode:false}")
    private boolean demoMode;

    /** 운영 기본 false: streamUrl=BE 프록시. true(개발/로컬): Aura 직접 URL */
    @Value("${synapse.stream-url-use-aura-direct:false}")
    private boolean streamUrlUseAuraDirect;

    @Value("${aura.base-url:http://localhost:9000}")
    private String auraBaseUrl;
    @Value("${aura.phase3.callback-base-url:}")
    private String phase3CallbackBaseUrl;
    @Value("${aura.phase3.callback-auth-token:}")
    private String phase3CallbackAuthToken;

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
        String streamUrl = beStreamUrl; // 운영 기본: 옵션 B (BE 프록시)

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

            if (phase3CallbackBaseUrl != null && !phase3CallbackBaseUrl.isBlank()) {
                // Phase3: POST /aura/internal/.../analysis-runs, callbacks 포함
                AuraPhase3TriggerRequest.Callbacks callbacks = AuraPhase3TriggerRequest.Callbacks.builder()
                        .resultCallbackUrl(phase3CallbackBaseUrl.trim())
                        .auth(phase3CallbackAuthToken != null && !phase3CallbackAuthToken.isBlank()
                                ? AuraPhase3TriggerRequest.Auth.builder().type("BEARER").token(phase3CallbackAuthToken).build()
                                : null)
                        .build();
                AuraPhase3TriggerRequest phase3Req = AuraPhase3TriggerRequest.builder()
                        .runId(runId)
                        .caseId(caseId)
                        .requestedBy(requestedBy != null ? requestedBy : "HUMAN")
                        .artifacts(evidenceSnapshot)
                        .callbacks(callbacks)
                        .options(request != null ? request.getOptions() : null)
                        .build();
                if (authorization == null || authorization.isBlank()) {
                    log.warn("Phase3 trigger requires Authorization header");
                    failRunWithMessage(run, "Phase3 trigger requires Authorization");
                } else {
                    AuraPhase3TriggerResponse phase3Res = auraCaseTabClient.triggerAnalyzePhase3(caseId, authorization, phase3Req);
                    if (streamUrlUseAuraDirect && phase3Res != null && phase3Res.getStreamPath() != null) {
                        String path = phase3Res.getStreamPath();
                        streamUrl = path.startsWith("http") ? path : (auraBaseUrl + (path.startsWith("/") ? path : "/" + path));
                    }
                }
            } else {
                BodyEvidenceDto bodyEvidence = buildBodyEvidence(agentCase);
                if (bodyEvidence == null) {
                    log.warn("Aura analyze body_evidence missing: caseId={} runId={} belnr={} buzei={}",
                            caseId, runId, agentCase.getBelnr(), agentCase.getBuzei());
                } else {
                    log.debug("Aura analyze body_evidence: caseId={} runId={} docId={} itemId={}",
                            caseId, runId, bodyEvidence.getDocId(), bodyEvidence.getItemId());
                }
                AuraAnalyzeRequest auraReq = AuraAnalyzeRequest.builder()
                        .caseId(caseId)
                        .runId(runId)
                        .mode(mode)
                        .requestedBy(requestedBy)
                        .evidence(evidenceSnapshot)
                        .options(request != null ? request.getOptions() : null)
                        .bodyEvidence(bodyEvidence)
                        .build();
                AuraAnalyzeResponse auraRes = auraCaseTabClient.triggerAnalyze(caseId, tenantId, authorization, userId, auraReq);
                if (auraRes != null) {
                    if ("disabled".equals(auraRes.getStatus())) {
                        failRunWithMessage(run, auraRes.getMessage());
                    } else if (streamUrlUseAuraDirect && auraRes.getStreamUrl() != null) {
                        streamUrl = auraRes.getStreamUrl();
                    }
                }
            }
        } catch (FeignException e) {
            if (e.status() == 202) {
                log.info("Aura analyze trigger accepted (202), run created: runId={}", runId);
            } else {
                log.warn("Aura analyze trigger failed, run created: runId={} status={}", runId, e.status());
                failRunWithMessage(run, "Aura analyze trigger failed: " + e.status());
            }
        }

        if (!streamUrlUseAuraDirect) {
            streamUrl = beStreamUrl;
        }

        logAudit(tenantId, caseId, runId, null,
                run.getStatus().equals(CaseAnalysisRun.STATUS_FAILED) ? AuditEventConstants.TYPE_RUN_FAILED : AuditEventConstants.TYPE_RUN_STARTED,
                "ANALYSIS_RUN", runId.toString(), Map.of("status", run.getStatus()), null);

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
     * 케이스 evidence snapshot 생성 — Aura 트리거 요청용 (Phase3 입력 패키지 표준화).
     * evidence, ragRefs, document(header+items), openItems, party, lineage, policies 포함.
     * document 없음 케이스: open-item 기반 증적도 동일 구조(document 형태)로 매핑.
     */
    private JsonNode buildEvidenceSnapshot(AgentCase agentCase) {
        Long tenantId = agentCase.getTenantId();
        Long caseId = agentCase.getCaseId();
        ObjectNode snapshot = objectMapper.createObjectNode();

        JsonNode evidence = agentCase.getEvidenceJson();
        JsonNode ragRefs = agentCase.getRagRefsJson();
        if (evidence != null) snapshot.set("evidence", evidence);
        if (ragRefs != null) snapshot.set("ragRefs", ragRefs);

        caseQueryService.findCaseDetail(tenantId, caseId).ifPresent(detail -> {
            if (detail.getEvidence() != null && detail.getEvidence().getDocumentOrOpenItem() != null) {
                DocumentOrOpenItemDto docOrOi = detail.getEvidence().getDocumentOrOpenItem();
                ObjectNode document = objectMapper.createObjectNode();
                document.set("header", objectMapper.valueToTree(docOrOi.getHeaderSummary() != null ? docOrOi.getHeaderSummary() : Map.of()));
                document.set("items", objectMapper.valueToTree(docOrOi.getItems() != null ? docOrOi.getItems() : List.of()));
                document.put("type", docOrOi.getType());
                if (docOrOi.getDocKey() != null) document.put("docKey", docOrOi.getDocKey());
                snapshot.set("document", document);
            }
            if (detail.getEvidence() != null && detail.getEvidence().getRelatedPartyIds() != null) {
                snapshot.set("partyIds", objectMapper.valueToTree(detail.getEvidence().getRelatedPartyIds()));
            }
        });

        if (agentCase.getBukrs() != null && agentCase.getBelnr() != null && agentCase.getGjahr() != null) {
            List<FiOpenItem> openItems = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                    tenantId, agentCase.getBukrs(), agentCase.getBelnr(), agentCase.getGjahr());
            ArrayNode openItemsArray = objectMapper.createArrayNode();
            for (FiOpenItem oi : openItems) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("bukrs", oi.getBukrs());
                node.put("belnr", oi.getBelnr());
                node.put("gjahr", oi.getGjahr());
                node.put("buzei", oi.getBuzei());
                node.put("itemType", oi.getItemType());
                if (oi.getLifnr() != null) node.put("lifnr", oi.getLifnr());
                if (oi.getKunnr() != null) node.put("kunnr", oi.getKunnr());
                if (oi.getDueDate() != null) node.put("dueDate", oi.getDueDate().toString());
                if (oi.getOpenAmount() != null) node.put("openAmount", oi.getOpenAmount());
                if (oi.getCurrency() != null) node.put("currency", oi.getCurrency());
                node.put("paymentBlock", Boolean.TRUE.equals(oi.getPaymentBlock()));
                node.put("disputeFlag", Boolean.TRUE.equals(oi.getDisputeFlag()));
                openItemsArray.add(node);
            }
            snapshot.set("openItems", openItemsArray);
        }

        String docKey = (agentCase.getBukrs() != null && agentCase.getBelnr() != null && agentCase.getGjahr() != null)
                ? agentCase.getBukrs() + "-" + agentCase.getBelnr() + "-" + agentCase.getGjahr() : null;
        try {
            var lineageQuery = LineageQueryService.LineageQuery.builder()
                    .caseId(caseId)
                    .docKey(docKey)
                    .build();
            LineageResponseDto lineage = lineageQueryService.findLineage(tenantId, lineageQuery);
            snapshot.set("lineage", objectMapper.valueToTree(lineage));
        } catch (Exception e) {
            log.debug("Lineage not available for case {}: {}", caseId, e.getMessage());
        }

        snapshot.set("policies", objectMapper.createArrayNode());
        return snapshot;
    }

    /**
     * Aura Phase2 규격: body_evidence { doc_id, item_id } — doc_id=BELNR, item_id=BUZEI
     */
    private BodyEvidenceDto buildBodyEvidence(AgentCase agentCase) {
        String docId = agentCase.getBelnr();
        String itemId = agentCase.getBuzei();
        if (docId == null && itemId == null) return null;
        return BodyEvidenceDto.builder()
                .docId(docId)
                .itemId(itemId)
                .build();
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
                    AuditEventConstants.TYPE_PROPOSAL_UPSERTED, "ACTION_PROPOSAL", proposal.getProposalId().toString(),
                    Map.of("type", "HOLD_PAYMENT", "status", "PROPOSED"), null);
        }

        logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), null,
                AuditEventConstants.TYPE_RUN_COMPLETED, "ANALYSIS_RUN", run.getRunId().toString(),
                Map.of("status", "COMPLETED", "score", 72), null);
    }

    @Transactional(readOnly = true)
    public Object getAnalysisRuns(Long tenantId, Long caseId, boolean latest) {
        return caseAnalysisQueryService.getAnalysisRuns(tenantId, caseId, latest);
    }

    @Transactional(readOnly = true)
    public AnalysisRunStatusDto getRunStatus(Long tenantId, UUID runId) {
        return caseAnalysisQueryService.getRunStatus(tenantId, runId);
    }

    @Transactional(readOnly = true)
    public CaseAnalysisDto getCaseAnalysis(Long tenantId, Long caseId, UUID runId) {
        return caseAnalysisQueryService.getCaseAnalysis(tenantId, caseId, runId);
    }

    @Transactional(readOnly = true)
    public List<CaseActionProposalDto> getActionProposals(Long tenantId, Long caseId, UUID runId) {
        return caseAnalysisQueryService.getActionProposals(tenantId, caseId, runId);
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
        if ("FAILED".equals(status)) {
            String errorMsg = payload.getError() != null ? payload.getError().toString() : "null";
            log.warn("Aura callback FAILED: runId={} caseId={} error={} auraTraceId={} status={}", 
                    runId, payload.getCaseId(), errorMsg, payload.getAuraTraceId(), status);
        } else if (status == null) {
            log.warn("Aura callback status is null: runId={} caseId={}", runId, payload.getCaseId());
            status = "FAILED"; // null이면 FAILED로 처리
        }
        run.setStatus("COMPLETED".equals(status) ? CaseAnalysisRun.STATUS_COMPLETED : CaseAnalysisRun.STATUS_FAILED);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(normalizeCallbackError("FAILED".equals(status), payload.getError()));
        if (payload.getAuraTraceId() != null) run.setAuraTraceId(payload.getAuraTraceId());
        runRepository.save(run);

        if ("COMPLETED".equals(status)) {
            if (payload.getAnalysis() != null) {
                saveResultAndProposalsFromPhase3(run, payload.getAnalysis(), payload.getProposals());
            } else if (payload.getFinalResult() != null) {
                saveResultAndProposals(run, payload.getFinalResult());
            }
            updateAgentCaseFromLatestResult(run);
        }

                logAudit(run.getTenantId(), run.getCaseId(), run.getRunId(), null,
                        "COMPLETED".equals(status) ? AuditEventConstants.TYPE_RUN_COMPLETED : AuditEventConstants.TYPE_RUN_FAILED,
                        "ANALYSIS_RUN", runId.toString(), Map.of("status", status), null);
    }

    /** Aura FAILED 시 error 필드 정규화: 문자열 또는 객체 { "error"|"message", "stage" } → DB 저장용 단일 문자열.
     * Aura 스키마: { "error": "메시지", "stage": "단계" } 또는 error가 문자열. BE는 error_message(TEXT)에 저장. */
    private String normalizeCallbackError(boolean isFailed, JsonNode errorNode) {
        if (!isFailed) return null;
        if (errorNode == null || errorNode.isNull()) return "Aura callback status: FAILED";
        if (errorNode.isTextual()) return errorNode.asText();
        if (errorNode.isObject()) {
            String msg = null;
            if (errorNode.has("error") && !errorNode.get("error").isNull()) msg = errorNode.get("error").asText();
            if (msg == null && errorNode.has("message") && !errorNode.get("message").isNull()) msg = errorNode.get("message").asText();
            if (msg == null) msg = "Aura callback status: FAILED";
            if (errorNode.has("stage") && !errorNode.get("stage").isNull()) {
                String stage = errorNode.get("stage").asText();
                if (stage != null && !stage.isBlank()) msg = msg + " (stage: " + stage + ")";
            }
            return msg;
        }
        return errorNode.toString();
    }

    /** Phase3 콜백: analysis + proposals 구조 저장 */
    private void saveResultAndProposalsFromPhase3(CaseAnalysisRun run,
                                                   AuraCallbackPayload.AnalysisBlock analysis,
                                                   List<AuraCallbackPayload.ProposalItem> proposals) {
        JsonNode confidenceJson = analysis.getConfidence() != null
                ? objectMapper.valueToTree(analysis.getConfidence()) : null;
        ArrayNode evidenceJson = objectMapper.createArrayNode();
        if (analysis.getEvidence() != null) analysis.getEvidence().forEach(m -> evidenceJson.add(objectMapper.valueToTree(m)));
        ArrayNode ragRefsJson = objectMapper.createArrayNode();
        if (analysis.getRagRefs() != null) analysis.getRagRefs().forEach(m -> ragRefsJson.add(objectMapper.valueToTree(m)));
        JsonNode evidenceMapJson = buildEvidenceMap(analysis.getEvidence(), analysis.getRagRefs());
        CaseAnalysisResult result = CaseAnalysisResult.builder()
                .runId(run.getRunId())
                .score(analysis.getScore() != null ? BigDecimal.valueOf(analysis.getScore()) : null)
                .severity(analysis.getSeverity())
                .reasonText(analysis.getReasonText())
                .confidenceJson(confidenceJson)
                .evidenceJson(evidenceJson.isEmpty() ? null : evidenceJson)
                .similarJson(null)
                .ragRefsJson(ragRefsJson.isEmpty() ? null : ragRefsJson)
                .evidenceMapJson(evidenceMapJson)
                .createdAt(Instant.now())
                .build();
        resultRepository.save(result);
        saveProposalsFromItems(run, proposals);
    }

    private void saveResultAndProposals(CaseAnalysisRun run, AuraCallbackPayload.FinalResult fr) {
        JsonNode confidenceJson = fr.getConfidence();
        ArrayNode evidenceJson = objectMapper.createArrayNode();
        if (fr.getEvidence() != null) fr.getEvidence().forEach(m -> evidenceJson.add(objectMapper.valueToTree(m)));
        ArrayNode similarJson = objectMapper.createArrayNode();
        if (fr.getSimilar() != null) fr.getSimilar().forEach(m -> similarJson.add(objectMapper.valueToTree(m)));
        ArrayNode ragRefsJson = objectMapper.createArrayNode();
        if (fr.getRagRefs() != null) fr.getRagRefs().forEach(m -> ragRefsJson.add(objectMapper.valueToTree(m)));

        Integer riskScore = null;
        if (fr.getRiskScore() != null) {
            riskScore = fr.getRiskScore() instanceof Integer ? (Integer) fr.getRiskScore()
                    : (int) Math.round(fr.getRiskScore().doubleValue());
            riskScore = Math.max(0, Math.min(100, riskScore));
        }

        // V65: finalResult.decision_reason(구조화 JSON)이 있으면 그대로 evidence_map_json에 저장, 없으면 evidence/ragRefs로 1:1 매핑 생성
        JsonNode evidenceMapJson = (fr.getDecisionReason() != null && !fr.getDecisionReason().isNull())
                ? fr.getDecisionReason()
                : buildEvidenceMap(fr.getEvidence(), fr.getRagRefs());
        CaseAnalysisResult result = CaseAnalysisResult.builder()
                .runId(run.getRunId())
                .score(fr.getScore() != null ? BigDecimal.valueOf(fr.getScore()) : null)
                .severity(fr.getSeverity())
                .reasonText(fr.getReasonText())
                .riskScore(riskScore)
                .violationClause(fr.getViolationClause() != null ? fr.getViolationClause() : "")
                .reasoningSummary(fr.getReasoningSummary())
                .recommendedAction(fr.getRecommendedAction())
                .confidenceJson(confidenceJson)
                .evidenceJson(evidenceJson.isEmpty() ? null : evidenceJson)
                .similarJson(similarJson.isEmpty() ? null : similarJson)
                .ragRefsJson(ragRefsJson.isEmpty() ? null : ragRefsJson)
                .evidenceMapJson(evidenceMapJson)
                .createdAt(Instant.now())
                .build();
        resultRepository.save(result);
        saveProposalsFromItems(run, fr.getProposals());
    }

    /**
     * 사실-규정 매핑: evidence[i](전표) ↔ ragRefs[i](규정 청크) 1:1.
     * 반환: [{ docId, itemId, chunkId }, ...] (FE Side-by-Side/Split-View용).
     * Aura 콜백 규격: evidence/ragRefs는 snake_case(doc_id, item_id, chunk_id) 사용. camelCase(docId, itemId, chunkId)도 지원.
     */
    private JsonNode buildEvidenceMap(List<Map<String, Object>> evidence, List<Map<String, Object>> ragRefs) {
        if (evidence == null || evidence.isEmpty() || ragRefs == null || ragRefs.isEmpty()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        int len = Math.min(evidence.size(), ragRefs.size());
        for (int i = 0; i < len; i++) {
            Map<String, Object> ev = evidence.get(i);
            Map<String, Object> ref = ragRefs.get(i);
            String docId = getString(ev, "doc_id", "docId");
            String itemId = getString(ev, "item_id", "itemId");
            Long chunkId = getLong(ref, "chunk_id", "chunkId");
            if (docId == null && itemId == null && chunkId == null) continue;
            ObjectNode node = objectMapper.createObjectNode();
            if (docId != null) node.put("docId", docId);
            if (itemId != null) node.put("itemId", itemId);
            if (chunkId != null) node.put("chunkId", chunkId);
            arr.add(node);
        }
        return arr.isEmpty() ? null : arr;
    }

    private static String getString(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }

    private static Long getLong(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number) return ((Number) v).longValue();
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /** 분석 완료 시 agent_case의 위반 등급·판단 근거·점수를 최신 case_analysis_result로 갱신. */
    private void updateAgentCaseFromLatestResult(CaseAnalysisRun run) {
        resultRepository.findByRunId(run.getRunId()).ifPresent(result -> {
            agentCaseRepository.findByCaseIdAndTenantId(run.getCaseId(), run.getTenantId()).ifPresent(agentCase -> {
                if (result.getSeverity() != null && !result.getSeverity().isBlank()) {
                    agentCase.setSeverity(result.getSeverity());
                }
                if (result.getReasonText() != null) {
                    agentCase.setReasonText(result.getReasonText());
                }
                if (result.getScore() != null) {
                    agentCase.setScore(result.getScore());
                }
                if (result.getRagRefsJson() != null) {
                    agentCase.setRagRefsJson(result.getRagRefsJson());
                }
                agentCase.setUpdatedAt(Instant.now());
                agentCaseRepository.save(agentCase);
            });
        });
    }

    /** Aura 콜백에서 제안 목록 저장 (Phase3 / FinalResult 공통). 중복 dedupKey 스킵. */
    private void saveProposalsFromItems(CaseAnalysisRun run, List<AuraCallbackPayload.ProposalItem> items) {
        if (items == null) return;
        for (AuraCallbackPayload.ProposalItem p : items) {
            String type = p.getType() != null ? p.getType() : "UNKNOWN";
            String dedupKey = ProposalDedupKeyUtil.compute(type, p.getPayload(), p.getRationale());
            if (proposalRepository.existsByCaseIdAndRunIdAndDedupKey(run.getCaseId(), run.getRunId(), dedupKey)) continue;
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
                    AuditEventConstants.TYPE_PROPOSAL_UPSERTED, "ACTION_PROPOSAL", prop.getProposalId().toString(),
                    Map.of("type", prop.getType(), "status", "PROPOSED"), null);
        }
    }

    @Transactional
    public void approveProposal(Long tenantId, UUID proposalId, Long userId, String comment) {
        CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(proposalId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
        proposal.setStatus(CaseActionProposal.STATUS_APPROVED);
        proposal.setUpdatedAt(Instant.now());
        proposal.setDecidedBy(userId);
        proposal.setDecidedAt(Instant.now());
        proposal.setDecisionComment(comment);
        proposalRepository.save(proposal);
        Map<String, Object> auditMeta = new HashMap<>(Map.of("status", "APPROVED"));
        auditMeta.put("actorUserId", userId != null ? userId : "");
        if (comment != null && !comment.isBlank()) auditMeta.put("comment", comment);
        writeProposalDecidedAudit(tenantId, proposal, auditMeta);
    }

    @Transactional
    public void rejectProposal(Long tenantId, UUID proposalId, Long userId, String comment) {
        CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(proposalId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
        proposal.setStatus(CaseActionProposal.STATUS_REJECTED);
        proposal.setUpdatedAt(Instant.now());
        proposal.setDecidedBy(userId);
        proposal.setDecidedAt(Instant.now());
        proposal.setDecisionComment(comment);
        proposalRepository.save(proposal);
        Map<String, Object> auditMeta = new HashMap<>(Map.of("status", "REJECTED"));
        auditMeta.put("actorUserId", userId != null ? userId : "");
        if (comment != null && !comment.isBlank()) auditMeta.put("comment", comment);
        writeProposalDecidedAudit(tenantId, proposal, auditMeta);
    }

    private void writeProposalDecidedAudit(Long tenantId, CaseActionProposal proposal, Map<String, Object> auditMeta) {
        Long caseId = proposal.getCaseId();
        UUID runId = proposal.getRunId();
        UUID proposalId = proposal.getProposalId();
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "CASE_ANALYSIS");
        if (caseId != null) tags.put("caseId", caseId);
        if (runId != null) tags.put("runId", runId.toString());
        if (proposalId != null) tags.put("proposalId", proposalId.toString());
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_CASE, AuditEventConstants.TYPE_PROPOSAL_DECIDED,
                RESOURCE_TYPE_ACTION_PROPOSAL, proposalId.toString(),
                AuditEventConstants.ACTOR_SYSTEM, null, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, auditMeta, null, null, tags, null, null, null, null, null);
    }

    /**
     * Phase3: 액션 제안 실행(시뮬레이션). APPROVED 제안만 실행 가능.
     * case_action_execution에 결과 저장, ACTION_EXECUTE_SIM 감사.
     * gatewayRequestId 있으면 저장·감사·멱등(동일 ID 재요청 시 기존 결과 반환).
     *
     * @param requestJson 요청 본문(감사/추적용), null 가능
     * @param runIdForValidation FE가 보낸 runId와 제안의 runId 일치 검증, null이면 검증 생략
     */
    @Transactional
    public ProposalExecuteResponseDto executeProposal(Long tenantId, Long caseId, UUID proposalId, Long userId, String gatewayRequestId, JsonNode requestJson, UUID runIdForValidation) {
        if (proposalId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "proposalId는 필수입니다.");
        }
        if (gatewayRequestId != null && !gatewayRequestId.isBlank()) {
            Optional<CaseActionExecution> existing = executionRepository.findByTenantIdAndGatewayRequestId(tenantId, gatewayRequestId);
            if (existing.isPresent()) {
                CaseActionExecution ex = existing.get();
                Map<String, Object> sim = resultJsonToMap(ex.getResultJson());
                return ProposalExecuteResponseDto.builder()
                        .executionId(ex.getExecutionId())
                        .actionId(ex.getExecutionId() != null ? ex.getExecutionId().toString() : null)
                        .proposalId(ex.getProposalId())
                        .status(ex.getStatus())
                        .mode(ex.getMode())
                        .executedAt(ex.getExecutedAt())
                        .simulation(sim)
                        .build();
            }
        }

        CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(proposalId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
        if (!proposal.getCaseId().equals(caseId)) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "제안이 해당 케이스에 속하지 않습니다.");
        }
        if (!CaseActionProposal.STATUS_APPROVED.equals(proposal.getStatus())) {
            logAudit(tenantId, caseId, proposal.getRunId(), proposalId, AuditEventConstants.TYPE_ACTION_FAILED,
                    "ACTION_PROPOSAL", proposalId.toString(),
                    Map.of("error", "승인된 제안만 실행할 수 있습니다.", "status", proposal.getStatus()), gatewayRequestId);
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "승인된 제안만 실행할 수 있습니다. 현재 상태: " + proposal.getStatus());
        }
        if (runIdForValidation != null && proposal.getRunId() != null && !runIdForValidation.equals(proposal.getRunId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "runId가 제안의 runId와 일치하지 않습니다.");
        }

        try {
            Instant now = Instant.now();
            CaseActionExecution execution = CaseActionExecution.builder()
                    .tenantId(tenantId)
                    .caseId(caseId)
                    .runId(proposal.getRunId())
                    .proposalId(proposalId)
                    .actionType(proposal.getType())
                    .requestJson(requestJson)
                    .mode(CaseActionExecution.MODE_SIMULATION)
                    .status(CaseActionExecution.STATUS_COMPLETED)
                    .resultJson(objectMapper.createObjectNode().put("simulated", true).put("proposalType", proposal.getType()))
                    .executedBy(userId)
                    .executedAt(now)
                    .createdAt(now)
                    .gatewayRequestId(gatewayRequestId != null && !gatewayRequestId.isBlank() ? gatewayRequestId : null)
                    .build();
            execution = executionRepository.save(execution);

            proposal.setStatus(CaseActionProposal.STATUS_EXECUTED);
            proposal.setUpdatedAt(now);
            proposalRepository.save(proposal);

            Map<String, Object> afterJson = new HashMap<>(Map.of(
                    "executionId", execution.getExecutionId().toString(),
                    "proposalId", proposalId.toString(),
                    "status", CaseActionExecution.STATUS_COMPLETED,
                    "mode", CaseActionExecution.MODE_SIMULATION,
                    "actorUserId", userId != null ? userId : ""));
            if (gatewayRequestId != null && !gatewayRequestId.isBlank()) afterJson.put("gatewayRequestId", gatewayRequestId);
            Map<String, Object> auditEvidence = new HashMap<>(Map.of(
                    "runId", proposal.getRunId() != null ? proposal.getRunId().toString() : "",
                    "actionType", proposal.getType() != null ? proposal.getType() : "",
                    "simulate", true));
            if (proposalId != null) auditEvidence.put("proposalId", proposalId.toString());
            logAuditWithEvidence(tenantId, caseId, proposal.getRunId(), proposalId, AuditEventConstants.TYPE_ACTION_EXECUTE_SIM,
                    "ACTION_EXECUTION", execution.getExecutionId().toString(), afterJson, gatewayRequestId, auditEvidence);

            Map<String, Object> simulationMap = resultJsonToMap(execution.getResultJson());
            return ProposalExecuteResponseDto.builder()
                    .executionId(execution.getExecutionId())
                    .actionId(execution.getExecutionId() != null ? execution.getExecutionId().toString() : null)
                    .proposalId(proposalId)
                    .status(execution.getStatus())
                    .mode(execution.getMode())
                    .executedAt(execution.getExecutedAt())
                    .simulation(simulationMap)
                    .build();
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            proposal.setStatus(CaseActionProposal.STATUS_FAILED);
            proposal.setUpdatedAt(Instant.now());
            proposalRepository.save(proposal);
            String msg = e.getMessage() != null ? e.getMessage() : "실행 중 오류 발생";
            logAudit(tenantId, caseId, proposal.getRunId(), proposalId, AuditEventConstants.TYPE_ACTION_FAILED,
                    "ACTION_PROPOSAL", proposalId.toString(), Map.of("error", msg), gatewayRequestId);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, msg);
        }
    }

    /**
     * Phase3 표준: POST /api/synapse/actions/execute 진입점.
     * 권장 A: proposalId로 실행. 대안 B: actionType+payload로 실행(proposal 있으면 연결, 없으면 proposal_id null로 저장).
     */
    @Transactional
    public ProposalExecuteResponseDto executeAction(Long tenantId, ExecuteActionRequestDto req, Long userId) {
        if (req.getCaseId() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "caseId는 필수입니다.");
        }
        Long caseId = req.getCaseId();
        boolean simulate = req.getSimulate() != null ? req.getSimulate() : true;
        String gatewayRequestId = req.getGatewayRequestId() != null && !req.getGatewayRequestId().isBlank() ? req.getGatewayRequestId() : null;

        if (gatewayRequestId != null) {
            Optional<CaseActionExecution> existing = executionRepository.findByTenantIdAndGatewayRequestId(tenantId, gatewayRequestId);
            if (existing.isPresent()) {
                CaseActionExecution ex = existing.get();
                Map<String, Object> sim = resultJsonToMap(ex.getResultJson());
                return ProposalExecuteResponseDto.builder()
                        .executionId(ex.getExecutionId())
                        .actionId(ex.getExecutionId() != null ? ex.getExecutionId().toString() : null)
                        .proposalId(ex.getProposalId())
                        .status(ex.getStatus())
                        .mode(ex.getMode())
                        .executedAt(ex.getExecutedAt())
                        .simulation(sim)
                        .build();
            }
        }

        JsonNode requestJson = objectMapper.valueToTree(req);

        if (req.getProposalId() != null) {
            CaseActionProposal proposal = proposalRepository.findByProposalIdAndTenantId(req.getProposalId(), tenantId)
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "액션 제안을 찾을 수 없습니다."));
            if (!proposal.getCaseId().equals(caseId)) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "제안이 해당 케이스에 속하지 않습니다.");
            }
            if (req.getRunId() != null && !req.getRunId().equals(proposal.getRunId())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "runId가 제안의 runId와 일치하지 않습니다.");
            }
            return executeProposal(tenantId, caseId, req.getProposalId(), userId, gatewayRequestId, requestJson, req.getRunId());
        }

        if (req.getActionType() != null && !req.getActionType().isBlank()) {
            if (req.getRunId() == null) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "대안 B 사용 시 runId는 필수입니다.");
            }
            List<CaseActionProposal> proposals = proposalRepository.findByTenantIdAndCaseIdAndRunIdOrderByCreatedAtDesc(tenantId, caseId, req.getRunId());
            Optional<CaseActionProposal> approved = proposals.stream()
                    .filter(p -> CaseActionProposal.STATUS_APPROVED.equals(p.getStatus()) && req.getActionType().equals(p.getType()))
                    .findFirst();
            if (approved.isPresent()) {
                return executeProposal(tenantId, caseId, approved.get().getProposalId(), userId, gatewayRequestId, requestJson, req.getRunId());
            }
            // B without matching proposal: record execution with proposal_id=null
            Instant now = Instant.now();
            ObjectNode resultNode = objectMapper.createObjectNode()
                    .put("result", "BLOCK_WOULD_BE_APPLIED")
                    .put("affected", 1);
            resultNode.set("details", objectMapper.createObjectNode().put("rule", "PAYMENT_BLOCK_RULE_V1"));
            CaseActionExecution execution = CaseActionExecution.builder()
                    .tenantId(tenantId)
                    .caseId(caseId)
                    .runId(req.getRunId())
                    .proposalId(null)
                    .actionType(req.getActionType())
                    .requestJson(requestJson)
                    .mode(CaseActionExecution.MODE_SIMULATION)
                    .status(CaseActionExecution.STATUS_COMPLETED)
                    .resultJson(resultNode)
                    .executedBy(userId)
                    .executedAt(now)
                    .createdAt(now)
                    .gatewayRequestId(gatewayRequestId)
                    .build();
            execution = executionRepository.save(execution);
            Map<String, Object> afterJson = new HashMap<>(Map.of(
                    "executionId", execution.getExecutionId().toString(),
                    "status", CaseActionExecution.STATUS_COMPLETED,
                    "mode", CaseActionExecution.MODE_SIMULATION,
                    "actionType", req.getActionType()));
            if (gatewayRequestId != null) afterJson.put("gatewayRequestId", gatewayRequestId);
            Map<String, Object> execEvidence = new HashMap<>(Map.of(
                    "runId", req.getRunId().toString(),
                    "actionType", req.getActionType(),
                    "simulate", true));
            logAuditWithEvidence(tenantId, caseId, req.getRunId(), null, AuditEventConstants.TYPE_ACTION_EXECUTE_SIM,
                    "ACTION_EXECUTION", execution.getExecutionId().toString(), afterJson, gatewayRequestId, execEvidence);
            Map<String, Object> simulationMap = resultJsonToMap(execution.getResultJson());
            return ProposalExecuteResponseDto.builder()
                    .executionId(execution.getExecutionId())
                    .actionId(execution.getExecutionId() != null ? execution.getExecutionId().toString() : null)
                    .proposalId(null)
                    .status(execution.getStatus())
                    .mode(execution.getMode())
                    .executedAt(execution.getExecutedAt())
                    .simulation(simulationMap)
                    .build();
        }

        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "proposalId 또는 actionType이 필요합니다.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultJsonToMap(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return objectMapper.convertValue(node, MapStringObjectTypeRef.INSTANCE);
    }

    private static final class MapStringObjectTypeRef extends TypeReference<Map<String, Object>> {
        static final MapStringObjectTypeRef INSTANCE = new MapStringObjectTypeRef();
    }

    private void logAudit(Long tenantId, Long caseId, UUID runId, UUID proposalId, String eventType, String resourceType, String resourceId, Map<String, Object> afterJson, String gatewayRequestId) {
        logAuditWithEvidence(tenantId, caseId, runId, proposalId, eventType, resourceType, resourceId, afterJson, gatewayRequestId, null);
    }

    private void logAuditWithEvidence(Long tenantId, Long caseId, UUID runId, UUID proposalId, String eventType, String resourceType, String resourceId, Map<String, Object> afterJson, String gatewayRequestId, Map<String, Object> evidenceJson) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "CASE_ANALYSIS");
        if (caseId != null) tags.put("caseId", caseId);
        if (runId != null) tags.put("runId", runId.toString());
        if (proposalId != null) tags.put("proposalId", proposalId.toString());
        String outcome = AuditEventConstants.TYPE_ACTION_FAILED.equals(eventType)
                ? AuditEventConstants.OUTCOME_FAILED : AuditEventConstants.OUTCOME_SUCCESS;
        String severity = AuditEventConstants.TYPE_ACTION_FAILED.equals(eventType)
                ? AuditEventConstants.SEVERITY_WARN : AuditEventConstants.SEVERITY_INFO;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_CASE, eventType,
                resourceType, resourceId,
                AuditEventConstants.ACTOR_SYSTEM, null, null, null, AuditEventConstants.CHANNEL_API,
                outcome, severity,
                null, afterJson, null, evidenceJson, tags, null, null, gatewayRequestId, null, null);
    }
}
