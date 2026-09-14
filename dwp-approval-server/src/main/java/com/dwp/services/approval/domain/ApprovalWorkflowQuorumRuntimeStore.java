package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class ApprovalWorkflowQuorumRuntimeStore {
    final NamedParameterJdbcTemplate jdbc;
    final ObjectMapper json;

    record Policy(long version, String sha256, int rejectLength, int warningPercent, int breachPercent,
            List<Map<String, Object>> references) { }
    record Context(Pins pins, UUID formVersionId, long requesterUserId, UUID requesterPersonId, int payloadRevision,
            String payloadSha256, int rejectLength, Policy policy) { }
    record StageRow(UUID stepId, long generation, String key, String status, long version,
            Context context, Snapshot snapshot, String definition, Instant dueAt) { }

    ApprovalWorkflowQuorumRuntimeStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = mapper.copy().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    MapSqlParameterSource scope(long tenant, UUID request) {
        return new MapSqlParameterSource().addValue("tenant", tenant).addValue("request", request);
    }

    Instant now() { return jdbc.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The quorum runtime evidence is stale."); }
    static void changed(int count) { if (count != 1) throw conflict(); }

    String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Quorum evidence serialization failed", exception); }
    }
    <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Stored quorum evidence is invalid", exception); }
    }
    Map<String, Object> object(String value) {
        try { return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Stored quorum object is invalid", exception); }
    }
    String hash(String value) { return ApprovalFormSchemaV2Canonical.sha256(value); }

    void lockRequest(long tenant, UUID request) {
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(tenant, request);
        var p = scope(tenant, request);
        var active = jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant "
                + "AND lifecycle_state='ACTIVE' FOR SHARE", p);
        if (active.isEmpty()) throw new BaseException(ErrorCode.FORBIDDEN);
        var rows = jdbc.queryForList("SELECT status FROM apr_requests WHERE tenant_id=:tenant "
                + "AND request_id=:request AND deleted_at IS NULL FOR UPDATE", p);
        if (rows.size() != 1 || !"IN_REVIEW".equals(rows.getFirst().get("status"))) throw conflict();
    }

    Policy policy(long tenant, UUID request) {
        return policy(tenant, request, true);
    }

    Policy policy(long tenant, UUID request, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT policy.policy_id, policy.policy_key, policy.version, policy.enforcement_mode,
                       policy.lifecycle_state, policy.rule_payload::text AS rule
                  FROM apr_policy_rules policy JOIN apr_requests request ON request.tenant_id=policy.tenant_id
                  JOIN apr_workflow_versions version ON version.tenant_id=request.tenant_id
                   AND version.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=version.tenant_id
                   AND workflow.workflow_id=version.workflow_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request
                   AND policy.management_resource_set_key=workflow.management_resource_set_key
                   AND policy.policy_key IN ('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION')
                 ORDER BY policy.policy_key
                """ + (lock ? " FOR SHARE OF policy" : ""), scope(tenant, request));
        if (rows.size() != 3 || rows.stream().anyMatch(row -> !"ACTIVE".equals(row.get("lifecycle_state")))) {
            throw unavailable("The chosen workflow policy snapshot is incomplete.");
        }
        Map<String, Object> reject = rows.stream().filter(row -> "REQUIRE_REJECT_REASON".equals(row.get("policy_key")))
                .findFirst().orElseThrow();
        Map<String, Object> sla = rows.stream().filter(row -> "SLA_ESCALATION".equals(row.get("policy_key"))).findFirst().orElseThrow();
        Map<?, ?> rejectRule = read((String) reject.get("rule"), Map.class);
        Map<?, ?> slaRule = read((String) sla.get("rule"), Map.class);
        int minimum = integer(rejectRule.get("minimumLength"), 4, 1000);
        int warning = integer(slaRule.get("warningPercent"), 1, 99);
        int breach = integer(slaRule.get("breachPercent"), warning, 100);
        // This is the selected SLA row's zero-based CAS version + 1, never MAX of unrelated policy rows.
        long version = Math.addExact(((Number) sla.get("version")).longValue(), 1);
        List<Map<String, Object>> refs = rows.stream().map(row -> Map.<String, Object>of(
                "policyId", row.get("policy_id").toString(), "key", row.get("policy_key"),
                "rowVersion", row.get("version"), "enforcement", row.get("enforcement_mode"),
                "rule", read((String) row.get("rule"), Map.class))).toList();
        return new Policy(version, hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(
                Map.of("references", refs)))), minimum, warning, breach, refs);
    }

    private int integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Integer number) || number < minimum || number > maximum) {
            throw unavailable("The chosen runtime policy is not a bounded integer contract.");
        }
        return number;
    }

    Context context(long tenant, UUID request, ApprovalWorkflowQuorumDefinition definition, Policy policy) {
        return context(tenant, request, definition, policy, true);
    }

    Context context(long tenant, UUID request, ApprovalWorkflowQuorumDefinition definition, Policy policy, boolean lock) {
        return jdbc.query("""
                SELECT request.*, workflow.version_number, workflow.definition_sha256, workflow.definition::text,
                       workflow.lifecycle_state AS workflow_state, form.schema_sha256,
                       payload.schema_version AS payload_revision, payload.payload_sha256
                  FROM apr_requests request JOIN apr_workflow_versions workflow
                    ON workflow.tenant_id=request.tenant_id AND workflow.workflow_version_id=request.workflow_version_id
                  JOIN apr_form_versions form ON form.tenant_id=request.tenant_id AND form.form_version_id=request.form_version_id
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request
                """ + (lock ? " FOR SHARE OF workflow, form, payload" : ""), scope(tenant, request), rs -> {
            if (!rs.next() || !"PUBLISHED".equals(rs.getString("workflow_state"))) throw conflict();
            var actual = ApprovalWorkflowQuorumDefinition.compile(rs.getString("definition"));
            if (!actual.sha256().equals(definition.sha256())
                    || !actual.sha256().equals(rs.getString("definition_sha256").trim())) throw conflict();
            Pins pins = new Pins(tenant, rs.getObject("workflow_version_id", UUID.class), rs.getInt("version_number"),
                    actual.sha256(), rs.getString("schema_sha256").trim(), policy.version(), policy.sha256());
            return new Context(pins, rs.getObject("form_version_id", UUID.class), rs.getLong("requester_user_id"), rs.getObject("requester_person_public_id", UUID.class),
                    rs.getInt("payload_revision"), rs.getString("payload_sha256").trim(), policy.rejectLength(), policy);
        });
    }

    void verify(Context frozen, long tenant, UUID request, String definition) {
        Policy currentPolicy = policy(tenant, request);
        if (currentPolicy.version() != frozen.policy().version() || !currentPolicy.sha256().equals(frozen.policy().sha256())) throw conflict();
        Context current = context(tenant, request, ApprovalWorkflowQuorumDefinition.compile(definition), frozen.policy());
        if (!current.equals(frozen)) throw conflict();
    }

    List<StageRow> stages(long tenant, UUID request, boolean lock) {
        return generation(tenant, request, null, lock);
    }

    List<StageRow> generation(long tenant, UUID request, Long generation, boolean lock) {
        return jdbc.query("SELECT * FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request "
                + "AND generation=" + (generation == null
                    ? "(SELECT MAX(generation) FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request) " : ":generation ")
                + "ORDER BY stage_key" + (lock ? " FOR UPDATE" : ""), scope(tenant, request).addValue("generation", generation), (rs, index) ->
                new StageRow(rs.getObject("step_id", UUID.class), rs.getLong("generation"), rs.getString("stage_key"),
                        rs.getString("status"), rs.getLong("version"), read(rs.getString("context"), Context.class),
                        rs.getString("snapshot") == null ? null : read(rs.getString("snapshot"), Snapshot.class),
                        rs.getString("definition_canonical"), rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant()));
    }

    List<Vote> votes(long tenant, UUID request, StageRow stage) {
        return jdbc.query("SELECT evidence::text FROM apr_quorum_votes WHERE tenant_id=:tenant AND request_id=:request "
                + "AND step_id=:step AND generation=:generation ORDER BY stage_version", scope(tenant, request)
                .addValue("step", stage.stepId()).addValue("generation", stage.generation()),
                (rs, index) -> read(rs.getString(1), Vote.class));
    }
    record StoredVote(UUID voteId,UUID taskId,Vote vote) { }
    List<StoredVote> storedVotes(long tenant,UUID request,StageRow stage) {
        return jdbc.query("SELECT vote_id,task_id,evidence::text FROM apr_quorum_votes WHERE tenant_id=:tenant AND request_id=:request "
                +"AND step_id=:step AND generation=:generation ORDER BY stage_version",scope(tenant,request)
                .addValue("step",stage.stepId()).addValue("generation",stage.generation()),
                (rs,index)->new StoredVote(rs.getObject("vote_id",UUID.class),rs.getObject("task_id",UUID.class),read(rs.getString("evidence"),Vote.class)));
    }
    UUID taskIdForSeat(long tenant,UUID request,StageRow stage,Candidate candidate) {
        var rows=jdbc.queryForList("SELECT task_id FROM apr_quorum_candidates WHERE tenant_id=:tenant AND request_id=:request "
                +"AND step_id=:step AND generation=:generation AND principal_user_id=:principal AND principal_person_id=:person",
                scope(tenant,request).addValue("step",stage.stepId()).addValue("generation",stage.generation())
                .addValue("principal",candidate.userId()).addValue("person",candidate.personPublicId()));
        if(rows.size()!=1) throw conflict();return (UUID)rows.getFirst().get("task_id");
    }
}
