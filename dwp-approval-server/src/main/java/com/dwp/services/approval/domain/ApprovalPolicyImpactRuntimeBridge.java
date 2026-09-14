package com.dwp.services.approval.domain;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactEvaluator;
import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Additive read-only access to package-private owner codecs; no runtime command is invoked. */
public final class ApprovalPolicyImpactRuntimeBridge implements ApprovalPolicyImpactRuntimePort {
    private final ApprovalCommandRepository legacy;
    private final ApprovalWorkflowQuorumRuntimeStore store;
    public ApprovalPolicyImpactRuntimeBridge(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        legacy = new ApprovalCommandRepository(jdbc, mapper);
        store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper);
    }
    @Override public void validate(String key, Rules rules) {
        if (key.equals("REQUIRE_REJECT_REASON")) ApprovalPolicyImpactEvaluator.integer(rules.rule().get("minimumLength"), 4, 1000);
        if (key.equals("SLA_ESCALATION")) {
            int warning = ApprovalPolicyImpactEvaluator.integer(rules.rule().get("warningPercent"), 1, 99);
            ApprovalPolicyImpactEvaluator.integer(rules.rule().get("breachPercent"), warning, 100);
        }
        legacy.validatePolicyRule(key, rules.rule());
    }
    @Override public int legacyRejectMinimum(Rules rules) {
        var actual = new ApprovalCommandJdbcRepository.PolicyRuntime(rules.enforcementMode(), rules.lifecycleState(), rules.rule());
        return actual.blocks() ? actual.integer("minimumLength", 8, 4, 1000) : 0;
    }
    @Override public String digest(Object value) {
        if (!(value instanceof Map<?, ?> object)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        Map<String, Object> captured = new java.util.LinkedHashMap<>();
        object.forEach((key, field) -> captured.put((String) key, field));
        return ApprovalFormSchemaV2Canonical.sha256(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(captured)));
    }
    @Override public boolean typed(String raw) {
        var object = store.object(raw);
        Object marker = object.get("schemaContract");
        if (marker == null) {
            legacy.runtimeSteps(raw, 60);
            return false;
        }
        if (!"DWP_APPROVAL_WORKFLOW_QUORUM_V2".equals(marker)) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        ApprovalWorkflowQuorumDefinition.compile(raw);
        return true;
    }
    @Override public RuntimeDifference quorum(long tenant, UUID request, Head selected) {
        var current = store.policy(tenant, request, false);
        var references = new ArrayList<Map<String, Object>>();
        boolean applicable = false;
        for (var reference : current.references()) {
            if (!selected.policyId().toString().equals(reference.get("policyId"))) { references.add(reference); continue; }
            applicable = true;
            if (((Number) reference.get("rowVersion")).longValue() != selected.rowVersion())
                throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
            var next = new java.util.TreeMap<String, Object>(reference);
            next.put("rowVersion", Math.addExact(selected.rowVersion(), 1));
            next.put("enforcement", selected.pending().enforcementMode()); next.put("rule", selected.pending().rule());
            references.add(next);
        }
        String proposedHash = applicable ? digest(Map.of("references", references)) : current.sha256();
        boolean unavailable = applicable && !"ACTIVE".equals(selected.pending().lifecycleState());
        boolean changed = applicable && switch (selected.policyKey()) {
            case "REQUIRE_REJECT_REASON", "SLA_ESCALATION" -> !selected.current().rule().equals(selected.pending().rule());
            default -> false;
        };
        var stages = store.stages(tenant, request, false);
        if (stages.size() > 64) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        boolean stale = false;
        boolean invalidates = false;
        int immutable = 0;
        for (var stage : stages) {
            immutable++;
            if (stage.snapshot() != null) {
                var votes = store.jdbc.query("SELECT evidence::text FROM apr_quorum_votes WHERE tenant_id=:tenant "
                        + "AND request_id=:request AND step_id=:step AND generation=:generation ORDER BY stage_version LIMIT 1001",
                        store.scope(tenant, request).addValue("step", stage.stepId()).addValue("generation", stage.generation()),
                        (rs, index) -> store.read(rs.getString(1), ApprovalWorkflowQuorum.Vote.class));
                if (votes.size() > 1000) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                // Only the actual frozen state is evaluated; proposed rules never reinterpret accepted votes.
                new ApprovalWorkflowQuorumEvaluator().evaluate(new ApprovalWorkflowQuorum.State(stage.snapshot(), stage.version(), votes));
            }
            if (java.util.Set.of("WAITING", "IN_PROGRESS").contains(stage.status())) {
                stale |= !current.sha256().equals(stage.context().policy().sha256())
                        || current.version() != stage.context().policy().version();
                invalidates |= !proposedHash.equals(stage.context().policy().sha256()) || unavailable;
            }
        }
        Integer timers = store.jdbc.queryForObject("SELECT count(*) FROM apr_quorum_sla_timers WHERE tenant_id=:tenant "
                + "AND request_id=:request AND status IN ('PENDING','CLAIMED')", store.scope(tenant, request), Integer.class);
        return new RuntimeDifference(stale, invalidates, changed, unavailable, immutable,
                timers == null ? 0 : timers, current.sha256(), proposedHash);
    }
}
