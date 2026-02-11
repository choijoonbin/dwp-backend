package com.dwp.services.synapsex.service.action;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.AgentCaseStatus;
import com.dwp.services.synapsex.entity.AgentActionStatus;
import com.dwp.services.synapsex.entity.ReconResult;
import com.dwp.services.synapsex.entity.AgentCaseActionHistory;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentCaseActionHistoryRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.ReconResultRepository;
import com.dwp.services.synapsex.event.ActionCompletedEvent;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 Actions 명령 서비스 (생성, 승인, 실행)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionCommandService {

    private static final String FI_STATUS_FINALIZED = "FINALIZED";
    private static final String FI_STATUS_REJECTED = "REJECTED";
    private static final String RECON_STATUS_PASS = "PASS";
    private static final String HISTORY_ACTION_APPROVE = "APPROVE";
    private static final String HISTORY_ACTION_REJECT = "REJECT";

    private final AgentActionRepository agentActionRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AgentCaseActionHistoryRepository actionHistoryRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final ReconResultRepository reconResultRepository;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public ActionDetailDto createAction(Long tenantId, Long caseId, String actionType, JsonNode payload,
                                        Long requestedByUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Case not found: " + caseId));

        // Phase A: 동일 caseId+actionType에 open 상태 proposal 중복 방지
        if (agentActionRepository.existsByTenantIdAndCaseIdAndActionTypeAndStatusIn(tenantId, caseId, actionType,
                List.of(AgentActionStatus.PROPOSED, AgentActionStatus.PENDING_APPROVAL, AgentActionStatus.APPROVED, AgentActionStatus.PLANNED))) {
            throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "동일 케이스에 동일 actionType의 승인 대기 중인 제안이 이미 존재합니다.");
        }

        AgentAction action = AgentAction.builder()
                .tenantId(tenantId)
                .caseId(caseId)
                .actionType(actionType)
                .actionPayload(payload)
                .payloadJson(payload)
                .requestedByUserId(requestedByUserId)
                .requestedByActorType("USER")
                .status(AgentActionStatus.PROPOSED)
                .plannedAt(Instant.now())
                .executedBy("PENDING")
                .build();
        action = agentActionRepository.save(action);

        JsonNode simBefore = computeSimulationBefore(case_, action);
        JsonNode simAfter = computeSimulationAfter(case_, action, payload);
        JsonNode diff = computeDiff(simBefore, simAfter);

        action.setSimulationBefore(simBefore);
        action.setSimulationAfter(simAfter);
        action.setDiffJson(diff);
        action = agentActionRepository.save(action);

        Map<String, Object> afterJson = new HashMap<>();
        afterJson.put("actionId", action.getActionId());
        afterJson.put("caseId", action.getCaseId());
        afterJson.put("actionType", action.getActionType());
        afterJson.put("status", "PROPOSED");
        auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_PROPOSE, action.getActionId(), action.getCaseId(),
                requestedByUserId, AuditEventConstants.OUTCOME_SUCCESS,
                null, afterJson,
                ipAddress, userAgent, gatewayRequestId);

        return ActionDetailDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus() != null ? action.getStatus().name() : null)
                .payload(action.getPayloadJson())
                .simulationBefore(action.getSimulationBefore())
                .simulationAfter(action.getSimulationAfter())
                .diffJson(action.getDiffJson())
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                .build();
    }

    private JsonNode computeSimulationBefore(AgentCase case_, AgentAction action) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("caseId", case_.getCaseId())
                .put("status", case_.getStatus() != null ? case_.getStatus().name() : null)
                .put("actionType", action.getActionType());
    }

    private JsonNode computeSimulationAfter(AgentCase case_, AgentAction action, JsonNode payload) {
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("caseId", case_.getCaseId())
                .put("actionType", action.getActionType());
        if (payload != null && payload.isObject()) {
            node.set("payload", payload);
        }
        return node;
    }

    private JsonNode computeDiff(JsonNode before, JsonNode after) {
        com.fasterxml.jackson.databind.node.ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.set("before", before);
        node.set("after", after);
        return node;
    }

    /**
     * Phase 6 HITL 완료: 승인 시 한 트랜잭션으로 (1) agent_case RESOLVED, (2) recon_result PASS,
     * (3) fi_doc_header FINALIZED, (4) agent_case_action_history INSERT, (5) ActionCompletedEvent 발행(커밋 직후 Redis/웹훅).
     */
    @Transactional
    public AgentAction approveAction(Long tenantId, Long actionId, Long actorUserId,
                                     String comment,
                                     String ipAddress, String userAgent, String gatewayRequestId,
                                     String traceId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Action not found: " + actionId));

        // Phase A: idempotent - 이미 APPROVED면 200 no-op
        if (action.getStatus() == AgentActionStatus.APPROVED) {
            return action;
        }

        String oldStatus = action.getStatus() != null ? action.getStatus().name() : null;
        action.setStatus(AgentActionStatus.APPROVED);
        action.setUpdatedAt(Instant.now());
        action = agentActionRepository.save(action);

        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(action.getCaseId(), tenantId).orElse(null);
        if (case_ != null) {
            case_.setStatus(AgentCaseStatus.RESOLVED);
            case_.setUpdatedAt(Instant.now());
            agentCaseRepository.save(case_);

            String bukrs = case_.getBukrs();
            String belnr = case_.getBelnr();
            String gjahr = case_.getGjahr();
            Map<String, Object> docSummary = new HashMap<>();
            if (bukrs != null && !bukrs.isBlank() && belnr != null && !belnr.isBlank() && gjahr != null && !gjahr.isBlank()) {
                docSummary.put("bukrs", bukrs);
                docSummary.put("belnr", belnr);
                docSummary.put("gjahr", gjahr);
                fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr)
                        .ifPresent(header -> {
                            header.setStatusCode(FI_STATUS_FINALIZED);
                            header.setUpdatedAt(Instant.now());
                            header.setLastChangeTs(Instant.now());
                            fiDocHeaderRepository.save(header);
                            docSummary.put("status_code", FI_STATUS_FINALIZED);
                        });
                String resourceKeyPrefix = bukrs + "-" + belnr + "-" + gjahr;
                List<ReconResult> reconResults = reconResultRepository.findByTenantIdAndResourceKeyStartingWith(tenantId, resourceKeyPrefix);
                for (ReconResult r : reconResults) {
                    r.setStatus(RECON_STATUS_PASS);
                    reconResultRepository.save(r);
                }
            }

            // Phase 6: 조치 이력 즉시 기록 (누가, 왜, 어떻게)
            String actorId = actorUserId != null ? "USER:" + actorUserId : "SYSTEM";
            AgentCaseActionHistory history = AgentCaseActionHistory.builder()
                    .tenantId(tenantId)
                    .caseId(case_.getCaseId())
                    .actionType(HISTORY_ACTION_APPROVE)
                    .actorId(actorId)
                    .commentText(comment)
                    .actionAt(Instant.now())
                    .metadataJson(docSummary)
                    .build();
            actionHistoryRepository.save(history);

            int fiDocUpdated = docSummary.containsKey("status_code") ? 1 : 0;
            String caseIdStr = case_.getBelnr() != null && !case_.getBelnr().isBlank() ? case_.getBelnr() : String.valueOf(case_.getCaseId());
            applicationEventPublisher.publishEvent(new ActionCompletedEvent(this, tenantId, action.getCaseId(), actionId, HISTORY_ACTION_APPROVE,
                    caseIdStr, traceId != null ? traceId : "action-" + actionId, actorId, true, history.getId(), fiDocUpdated));
        } else {
            applicationEventPublisher.publishEvent(new ActionCompletedEvent(this, tenantId, action.getCaseId(), actionId, HISTORY_ACTION_APPROVE,
                    String.valueOf(action.getCaseId()), "action-" + actionId, null, true, 0L, 0));
        }

        Map<String, Object> approveAfter = new HashMap<>();
        approveAfter.put("status", "APPROVED");
        approveAfter.put("case_id", action.getCaseId());
        approveAfter.put("proposal_id", actionId);
        if (traceId != null) approveAfter.put("trace_id", traceId);
        auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_APPROVE, actionId, action.getCaseId(),
                actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("status", oldStatus != null ? oldStatus : ""), approveAfter,
                ipAddress, userAgent, gatewayRequestId, traceId, null);

        return action;
    }

    @Transactional
    public AgentAction executeAction(Long tenantId, Long actionId, Long actorUserId,
                                     String ipAddress, String userAgent, String gatewayRequestId,
                                     String traceId, String spanId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Action not found: " + actionId));

        if (action.getStatus() != AgentActionStatus.APPROVED) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Only APPROVED actions can be executed. Current status: " + (action.getStatus() != null ? action.getStatus().name() : null));
        }

        String oldStatus = action.getStatus().name();
        action.setStatus(AgentActionStatus.EXECUTING);
        action.setUpdatedAt(Instant.now());
        action = agentActionRepository.save(action);

        try {
            action.setStatus(AgentActionStatus.EXECUTED);
            action.setExecutedAt(Instant.now());
            action.setExecutedBy(actorUserId != null ? "USER:" + actorUserId : "SYSTEM");
            action.setUpdatedAt(Instant.now());
            action = agentActionRepository.save(action);

            auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_EXECUTE, actionId, action.getCaseId(),
                    actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                    Map.of("status", oldStatus), Map.of("status", "EXECUTED"),
                    ipAddress, userAgent, gatewayRequestId, traceId, spanId);
        } catch (Exception e) {
            action.setStatus(AgentActionStatus.FAILED);
            action.setFailureReason(e.getMessage());
            action.setErrorMessage(e.getMessage());
            action.setUpdatedAt(Instant.now());
            action = agentActionRepository.save(action);

            auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_FAILED, actionId, action.getCaseId(),
                    actorUserId, AuditEventConstants.OUTCOME_FAILED,
                    Map.of("status", oldStatus), Map.of("status", "FAILED", "error", e.getMessage()),
                    ipAddress, userAgent, gatewayRequestId, traceId, spanId);
            throw e;
        }
        return action;
    }

    @Transactional
    public AgentAction rejectAction(Long tenantId, Long actionId, Long actorUserId,
                                    String comment,
                                    String ipAddress, String userAgent, String gatewayRequestId,
                                    String traceId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Action not found: " + actionId));

        // Phase A: idempotent - 이미 CANCELED면 200 no-op
        if (action.getStatus() == AgentActionStatus.CANCELED) {
            return action;
        }

        String oldStatus = action.getStatus() != null ? action.getStatus().name() : null;
        action.setStatus(AgentActionStatus.CANCELED);
        action.setUpdatedAt(Instant.now());
        action = agentActionRepository.save(action);

        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(action.getCaseId(), tenantId).orElse(null);
        Map<String, Object> docSummary = new HashMap<>();
        if (case_ != null) {
            // Backend SoT: 거절 시에도 케이스 상태를 RESOLVED로 확정 (조치 완료)
            case_.setStatus(AgentCaseStatus.RESOLVED);
            case_.setUpdatedAt(Instant.now());
            agentCaseRepository.save(case_);

            String bukrs = case_.getBukrs();
            String belnr = case_.getBelnr();
            String gjahr = case_.getGjahr();
            if (bukrs != null && !bukrs.isBlank() && belnr != null && !belnr.isBlank() && gjahr != null && !gjahr.isBlank()) {
                docSummary.put("bukrs", bukrs);
                docSummary.put("belnr", belnr);
                docSummary.put("gjahr", gjahr);
                fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, bukrs, belnr, gjahr)
                        .ifPresent(header -> {
                            header.setStatusCode(FI_STATUS_REJECTED);
                            header.setUpdatedAt(Instant.now());
                            header.setLastChangeTs(Instant.now());
                            fiDocHeaderRepository.save(header);
                            docSummary.put("status_code", FI_STATUS_REJECTED);
                        });
            }
            String actorId = actorUserId != null ? "USER:" + actorUserId : "SYSTEM";
            AgentCaseActionHistory history = AgentCaseActionHistory.builder()
                    .tenantId(tenantId)
                    .caseId(case_.getCaseId())
                    .actionType(HISTORY_ACTION_REJECT)
                    .actorId(actorId)
                    .commentText(comment)
                    .actionAt(Instant.now())
                    .metadataJson(docSummary)
                    .build();
            actionHistoryRepository.save(history);

            int fiDocUpdated = docSummary.containsKey("status_code") ? 1 : 0;
            String caseIdStr = case_.getBelnr() != null && !case_.getBelnr().isBlank() ? case_.getBelnr() : String.valueOf(case_.getCaseId());
            applicationEventPublisher.publishEvent(new ActionCompletedEvent(this, tenantId, action.getCaseId(), actionId, HISTORY_ACTION_REJECT,
                    caseIdStr, traceId != null ? traceId : "action-" + actionId, actorId, false, history.getId(), fiDocUpdated));
        } else {
            applicationEventPublisher.publishEvent(new ActionCompletedEvent(this, tenantId, action.getCaseId(), actionId, HISTORY_ACTION_REJECT,
                    String.valueOf(action.getCaseId()), "action-" + actionId, null, false, 0L, 0));
        }

        Map<String, Object> rejectAfter = new HashMap<>();
        rejectAfter.put("status", "CANCELED");
        rejectAfter.put("case_id", action.getCaseId());
        rejectAfter.put("proposal_id", actionId);
        if (traceId != null) rejectAfter.put("trace_id", traceId);
        auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_REJECT, actionId, action.getCaseId(),
                actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("status", oldStatus != null ? oldStatus : ""), rejectAfter,
                ipAddress, userAgent, gatewayRequestId, traceId, null);

        return action;
    }

    @Transactional
    public AgentAction requestInfo(Long tenantId, Long actionId, Long actorUserId,
                                   String ipAddress, String userAgent, String gatewayRequestId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "Action not found: " + actionId));

        auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_REQUEST_INFO, actionId, action.getCaseId(),
                actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("actionId", actionId), Map.of("requested", "REQUEST_INFO"),
                ipAddress, userAgent, gatewayRequestId);
        return action;
    }
}
