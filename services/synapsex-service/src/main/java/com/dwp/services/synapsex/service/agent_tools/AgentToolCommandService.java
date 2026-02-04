package com.dwp.services.synapsex.service.agent_tools;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.agent_tools.ActionProposeResponse;
import com.dwp.services.synapsex.dto.agent_tools.ActionSimulateResponse;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentActionSimulation;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentActionSimulationRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.service.action.ActionCommandService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Tool Write API: simulate, propose, execute
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolCommandService {

    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final AgentActionSimulationRepository simulationRepository;
    private final ActionCommandService actionCommandService;
    private final PolicyEngine policyEngine;
    private final AuditWriter auditWriter;

    @Transactional
    public ActionSimulateResponse simulate(Long tenantId, Long caseId, String actionType, JsonNode payload,
                                          String actorType, Long actorUserId, String actorAgentId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        ObjectNode before = JsonNodeFactory.instance.objectNode()
                .put("caseId", case_.getCaseId())
                .put("status", case_.getStatus() != null ? case_.getStatus().name() : null)
                .put("caseType", case_.getCaseType())
                .put("actionType", actionType);

        ObjectNode after = JsonNodeFactory.instance.objectNode()
                .put("caseId", case_.getCaseId())
                .put("actionType", actionType);
        if (payload != null && payload.isObject()) {
            after.set("payload", payload);
        }

        List<String> validationErrors = new ArrayList<>();
        JsonNode amountNode = payload != null && payload.has("amount") ? payload.get("amount") : null;
        if (amountNode != null && amountNode.isNumber() && amountNode.decimalValue().compareTo(BigDecimal.ZERO) < 0) {
            validationErrors.add("amount must be non-negative");
        }

        ObjectNode validationJson = JsonNodeFactory.instance.objectNode();
        ArrayNode errArr = validationJson.arrayNode();
        validationErrors.forEach(errArr::add);
        validationJson.set("errors", errArr);

        ObjectNode predictedSapImpact = JsonNodeFactory.instance.objectNode()
                .put("estimated", "PLUGGABLE")
                .put("note", "SAP integration via Integration Outbox (future)");

        AgentActionSimulation sim = AgentActionSimulation.builder()
                .tenantId(tenantId)
                .caseId(caseId)
                .actionType(actionType)
                .payloadJson(payload)
                .beforeJson(before)
                .afterJson(after)
                .validationJson(validationJson)
                .createdByActor(actorType != null ? actorType : AuditEventConstants.ACTOR_AGENT)
                .createdById(actorUserId)
                .build();
        sim = simulationRepository.save(sim);

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("simulationId", sim.getSimulationId());
        evidence.put("caseId", caseId);
        evidence.put("actionType", actionType);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_SIMULATE,
                "AGENT_ACTION_SIMULATION", String.valueOf(sim.getSimulationId()),
                actorType != null ? actorType : AuditEventConstants.ACTOR_AGENT,
                actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_AGENT,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                Map.of("before", before), Map.of("after", after), null, evidence, null,
                null, null, null, null, null);

        return ActionSimulateResponse.builder()
                .beforePreview(before)
                .afterPreview(after)
                .validationErrors(validationErrors)
                .predictedSapImpact(predictedSapImpact)
                .simulationId(sim.getSimulationId())
                .build();
    }

    @Transactional
    public ActionProposeResponse propose(Long tenantId, Long caseId, String actionType, JsonNode payload,
                                        String actorType, Long actorUserId, String actorAgentId) {
        AgentCase case_ = agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        BigDecimal amount = null;
        if (payload != null && payload.has("amount") && payload.get("amount").isNumber()) {
            amount = payload.get("amount").decimalValue();
        }
        String result = policyEngine.evaluateAction(tenantId, caseId, actionType, payload,
                case_.getCaseType(), amount);

        boolean requiresApproval = "APPROVAL_REQUIRED".equals(result);
        if ("DENIED".equals(result)) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("guardrailResult", result);
            evidence.put("caseId", caseId);
            evidence.put("actionType", actionType);
            auditWriter.log(tenantId, AuditEventConstants.CATEGORY_POLICY, "ACCESS_DENIED",
                    "AGENT_ACTION", null,
                    actorType != null ? actorType : AuditEventConstants.ACTOR_AGENT,
                    actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_AGENT,
                    AuditEventConstants.OUTCOME_DENIED, AuditEventConstants.SEVERITY_WARN,
                    null, null, null, evidence, null,
                    null, null, null, null, null);
            return ActionProposeResponse.builder()
                    .requiresApproval(false)
                    .actionId(null)
                    .guardrailResult("DENIED")
                    .build();
        }

        var actionDto = actionCommandService.createAction(tenantId, caseId, actionType, payload,
                actorUserId, null, null, null);
        AgentAction action = agentActionRepository.findById(actionDto.getActionId())
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElseThrow();

        if (!requiresApproval) {
            actionCommandService.approveAction(tenantId, action.getActionId(), actorUserId, null, null, null);
        }

        Map<String, Object> afterMap = new HashMap<>();
        afterMap.put("actionId", action.getActionId());
        afterMap.put("status", action.getStatus() != null ? action.getStatus().name() : null);
        afterMap.put("requiresApproval", requiresApproval);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_PROPOSE,
                "AGENT_ACTION", String.valueOf(action.getActionId()),
                actorType != null ? actorType : AuditEventConstants.ACTOR_AGENT,
                actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_AGENT,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, afterMap, null, null, null,
                null, null, null, null, null);

        return ActionProposeResponse.builder()
                .requiresApproval(requiresApproval)
                .actionId(action.getActionId())
                .guardrailResult(result)
                .build();
    }

    @Transactional
    public AgentAction execute(Long tenantId, Long actionId, String actorType, Long actorUserId) {
        return actionCommandService.executeAction(tenantId, actionId, actorUserId, null, null, null);
    }
}
