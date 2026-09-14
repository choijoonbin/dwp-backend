package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed internal event snapshot. It does not assert future consumer-side runtime authority. */
final class ApprovalWorkflowQuorumSlaEvent {
    static final String CONTRACT = "DWP_APPROVAL_QUORUM_SLA_EVENT_V1";
    private ApprovalWorkflowQuorumSlaEvent() { }

    static Map<String, Object> payload(ApprovalWorkflowQuorumRuntimeStore store,
            UUID requestId, UUID timerId, long leaseEpoch, ApprovalWorkflowQuorumRuntimeStore.StageRow stage,
            List<Long> audience, String authorityRevision, Instant occurredAt) {
        List<Long> recipients = audience.stream().distinct().sorted().toList();
        if (recipients.isEmpty() || recipients.size() > MAX_CANDIDATES || recipients.size() != audience.size()) {
            throw unavailable("A complete unique bounded SLA audience is required.");
        }
        var params = store.scope(stage.context().pins().tenantId(), requestId).addValue("step", stage.stepId())
                .addValue("generation", stage.generation()).addValue("recipients", recipients);
        var request = store.jdbc.queryForMap("SELECT title,version,management_resource_set_key FROM apr_requests "
                + "WHERE tenant_id=:tenant AND request_id=:request", params);
        var seats = store.jdbc.query("""
                SELECT candidate.principal_user_id,candidate.principal_person_id,candidate.task_id,task.version
                  FROM apr_quorum_candidates candidate JOIN apr_tasks task ON task.tenant_id=candidate.tenant_id
                   AND task.request_id=candidate.request_id AND task.task_id=candidate.task_id AND task.step_id=candidate.step_id
                 WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.step_id=:step
                   AND candidate.generation=:generation AND candidate.principal_user_id IN (:recipients)
                 ORDER BY candidate.principal_user_id FOR SHARE OF candidate,task
                """, params, (row, index) -> Map.<String, Object>of("userId", row.getLong("principal_user_id"),
                        "personPublicId", row.getObject("principal_person_id").toString(),
                        "taskId", row.getObject("task_id").toString(), "taskVersion", row.getLong("version")));
        if (seats.size() != recipients.size()
                || !seats.stream().map(seat -> (Long) seat.get("userId")).toList().equals(recipients)) throw unavailable("The frozen SLA task audience changed.");
        var context = stage.context(); var pins = context.pins();
        var body = new LinkedHashMap<String, Object>();
        body.put("eventContract", CONTRACT);
        body.put("requestTitle", request.get("title"));
        body.put("occurredAt", occurredAt.toString());
        body.put("requestVersion", request.get("version"));
        body.put("managementResourceSetKey", request.get("management_resource_set_key"));
        body.put("stepId", stage.stepId().toString());
        body.put("stageKey", stage.key());
        body.put("stageVersion", stage.version());
        body.put("generation", stage.generation());
        body.put("workflowVersionId", pins.workflowVersionId().toString());
        body.put("workflowVersion", pins.workflowVersion());
        body.put("workflowDefinitionSha256", pins.workflowDefinitionSha256());
        body.put("formVersionId", context.formVersionId().toString());
        body.put("formSchemaSha256", pins.formSchemaSha256());
        body.put("payloadRevision", context.payloadRevision());
        body.put("payloadSha256", context.payloadSha256());
        body.put("policyVersion", pins.policyVersion());
        body.put("policySha256", pins.policySha256());
        body.put("timerId", timerId.toString());
        body.put("leaseEpoch", leaseEpoch);
        body.put("recipientUserIds", recipients);
        body.put("recipientSnapshotSha256", store.hash(ApprovalFormSchemaV2Canonical.json(
                ApprovalFormSchemaV2Canonical.freeze(Map.of("recipientUserIds", recipients, "recipientSeats", seats)))));
        body.put("recipientSeats", seats);
        body.put("authorityRevision", authorityRevision);
        return Map.copyOf(body);
    }
}
