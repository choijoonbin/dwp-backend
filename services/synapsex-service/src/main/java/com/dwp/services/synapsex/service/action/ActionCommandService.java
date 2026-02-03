package com.dwp.services.synapsex.service.action;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 2 Actions 명령 서비스 (생성, 승인, 실행)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionCommandService {

    private final AgentActionRepository agentActionRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AuditWriter auditWriter;

    @Transactional
    public ActionDetailDto createAction(Long tenantId, Long caseId, String actionType, JsonNode payload,
                                        Long requestedByUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        AgentAction action = AgentAction.builder()
                .tenantId(tenantId)
                .caseId(caseId)
                .actionType(actionType)
                .actionPayload(payload)
                .payloadJson(payload)
                .requestedByUserId(requestedByUserId)
                .requestedByActorType("USER")
                .status("PROPOSED")
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

        return ActionDetailDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus())
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
                .put("status", case_.getStatus())
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

    @Transactional
    public AgentAction approveAction(Long tenantId, Long actionId, Long actorUserId,
                                     String ipAddress, String userAgent, String gatewayRequestId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Action not found: " + actionId));

        String oldStatus = action.getStatus();
        action.setStatus("APPROVED");
        action.setUpdatedAt(Instant.now());
        action = agentActionRepository.save(action);

        auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_APPROVE, actionId, action.getCaseId(),
                actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("status", oldStatus), Map.of("status", "APPROVED"),
                ipAddress, userAgent, gatewayRequestId);
        return action;
    }

    @Transactional
    public AgentAction executeAction(Long tenantId, Long actionId, Long actorUserId,
                                     String ipAddress, String userAgent, String gatewayRequestId) {
        AgentAction action = agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Action not found: " + actionId));

        String oldStatus = action.getStatus();
        action.setStatus("EXECUTING");
        action.setUpdatedAt(Instant.now());
        action = agentActionRepository.save(action);

        try {
            action.setStatus("EXECUTED");
            action.setExecutedAt(Instant.now());
            action.setExecutedBy(actorUserId != null ? "USER:" + actorUserId : "SYSTEM");
            action.setUpdatedAt(Instant.now());
            action = agentActionRepository.save(action);

            auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_EXECUTE, actionId, action.getCaseId(),
                    actorUserId, AuditEventConstants.OUTCOME_SUCCESS,
                    Map.of("status", oldStatus), Map.of("status", "EXECUTED"),
                    ipAddress, userAgent, gatewayRequestId);
        } catch (Exception e) {
            action.setStatus("FAILED");
            action.setFailureReason(e.getMessage());
            action.setErrorMessage(e.getMessage());
            action.setUpdatedAt(Instant.now());
            action = agentActionRepository.save(action);

            auditWriter.logActionEvent(tenantId, AuditEventConstants.TYPE_FAILED, actionId, action.getCaseId(),
                    actorUserId, AuditEventConstants.OUTCOME_FAILED,
                    Map.of("status", oldStatus), Map.of("status", "FAILED", "error", e.getMessage()),
                    ipAddress, userAgent, gatewayRequestId);
            throw e;
        }
        return action;
    }
}
