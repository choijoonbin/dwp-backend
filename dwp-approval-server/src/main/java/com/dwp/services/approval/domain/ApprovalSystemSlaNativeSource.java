package com.dwp.services.approval.domain;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.systemslaauthority.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DB-only workload seal. It is not an Auth grant, a CandidatePool, or an end-user authority Window. */
public final class ApprovalSystemSlaNativeSource {
    private static final Set<String> ENVELOPE = Set.of("specVersion", "eventType", "tenantId", "requestId", "correlationId", "payload");
    private static final Set<String> PAYLOAD = Set.of("eventContract", "requestTitle", "occurredAt", "requestVersion", "managementResourceSetKey",
            "stepId", "stageKey", "stageVersion", "generation", "workflowVersionId", "workflowVersion", "workflowDefinitionSha256", "formVersionId",
            "formSchemaSha256", "payloadRevision", "payloadSha256", "policyVersion", "policySha256", "timerId", "leaseEpoch", "recipientUserIds",
            "recipientSnapshotSha256", "recipientSeats", "authorityRevision");
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final SystemSlaJson json;
    private final com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard live;
    public ApprovalSystemSlaNativeSource(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        store = new ApprovalWorkflowQuorumRuntimeStore(jdbc, mapper); json = new SystemSlaJson(mapper);
        live = new com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard(jdbc);
    }
    public static final class Seal {
        private final JsonNode bindings;
        private final String nativeVectorSha256;
        private final Instant validUntil;
        private final ApprovalWorkflowQuorumSlaRuntime.Lease lease;
        private final SystemSlaNotificationVerifier.Verified notification;
        private final Set<UUID> actionableTasks;
        private final JsonNode originalWitness;
        private final javax.sql.DataSource capturedSource;
        private final long capturedTransaction;
        private Seal(JsonNode bindings, String vector, Instant until, ApprovalWorkflowQuorumSlaRuntime.Lease lease,
                SystemSlaNotificationVerifier.Verified notification, Set<UUID> actionableTasks, JsonNode witness, javax.sql.DataSource source,long transaction) {
            this.bindings = bindings.deepCopy(); nativeVectorSha256 = vector; validUntil = until; this.lease = lease; this.notification = notification;
            this.actionableTasks = Set.copyOf(actionableTasks);
            originalWitness=witness.deepCopy();
            capturedSource=source; capturedTransaction=transaction;
        }
        public JsonNode bindings() { return bindings.deepCopy(); }
        public String nativeVectorSha256() { return nativeVectorSha256; }
        public Instant validUntil() { return validUntil; }
        public boolean delivery() { return notification != null; }
        public boolean taskEligible(UUID taskId) { return actionableTasks.contains(taskId); }
        public JsonNode originalWitness() { return originalWitness.deepCopy(); }
        public boolean capturedIn(javax.sql.DataSource source,long transaction) { return source==capturedSource && transaction==capturedTransaction; }
    }
    public Seal produce(ApprovalWorkflowQuorumSlaRuntime.Lease lease) {
        if (lease == null || lease.tenantId() < 1 || lease.timerId() == null || lease.requestId() == null || lease.stepId() == null
                || lease.generation() < 1 || lease.epoch() < 1 || lease.owner() == null || lease.until() == null) throw denied();
        return load(lease.tenantId(), lease.requestId(), lease.stepId(), lease.timerId(), lease, null);
    }
    public Seal deliver(SystemSlaNotificationVerifier.Verified notification) {
        mandatory(); if (notification == null || !notification.expiresAt().isAfter(store.now())) throw denied();
        var request = notification.request(); long tenant = integer(request, "tenantId", true); UUID id = uuid(request, "requestId");
        live.request(tenant, id);
        var p = store.scope(tenant, id).addValue("event", uuid(request, "eventId"));
        var timers = store.jdbc.queryForList("SELECT timer_id,step_id FROM apr_quorum_sla_timers WHERE tenant_id=:tenant AND request_id=:request AND event_id=:event", p);
        if (timers.size() != 1) throw changed();
        return load(tenant, id, (UUID) timers.getFirst().get("step_id"), (UUID) timers.getFirst().get("timer_id"), null, notification);
    }
    public void unchanged(Seal original) {
        if (original == null || !original.validUntil.isAfter(store.now())) throw changed();
        var current = original.delivery() ? deliver(original.notification) : produce(original.lease);
        if (!original.nativeVectorSha256.equals(current.nativeVectorSha256)
                || !json.digest(stable(original.bindings)).equals(json.digest(stable(current.bindings)))) throw changed();
    }
    private JsonNode stable(JsonNode bindings) {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) bindings.deepCopy(); value.remove("authorityValidUntil"); return value;
    }
    private Seal load(long tenant, UUID requestId, UUID stepId, UUID timerId, ApprovalWorkflowQuorumSlaRuntime.Lease lease,
            SystemSlaNotificationVerifier.Verified notification) {
        mandatory(); var p = store.scope(tenant, requestId).addValue("step", stepId).addValue("timer", timerId);
        if (lease == null) live.request(tenant, requestId); else live.writeRequest(tenant, requestId);
        var tenants = store.jdbc.queryForList("SELECT * FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE", p);
        if (tenants.size() != 1) throw denied();
        var requests = store.jdbc.queryForList("SELECT * FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request "
                + (lease == null ? "FOR SHARE" : "FOR UPDATE"), p);
        if (requests.size() != 1) throw changed(); var request = requests.getFirst();
        if (!"IN_REVIEW".equals(request.get("status")) || request.get("deleted_at") != null) throw changed();
        var stageRows = store.jdbc.queryForList("SELECT * FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step "
                + "AND generation=(SELECT MAX(generation) FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request) "
                + (lease == null ? "FOR SHARE" : "FOR UPDATE"), p);
        if (stageRows.size() != 1 || !"IN_PROGRESS".equals(stageRows.getFirst().get("status"))) throw changed();
        var stage = store.stages(tenant, requestId, false).stream().filter(row -> row.stepId().equals(stepId)).findFirst().orElseThrow(SystemSlaJson::changed);
        if (stage.snapshot() == null || stage.snapshot().candidates().isEmpty() || stage.snapshot().candidates().size() > 1000) throw changed();
        var steps = store.jdbc.queryForList("SELECT * FROM apr_steps WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step FOR SHARE", p);
        if (steps.size() != 1 || !"IN_PROGRESS".equals(steps.getFirst().get("status")) || !stage.key().equals(steps.getFirst().get("step_key"))) throw changed();
        var timers = store.jdbc.queryForList("SELECT * FROM apr_quorum_sla_timers WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND timer_id=:timer FOR UPDATE", p);
        if (timers.size() != 1) throw changed(); var timer = timers.getFirst(); Instant now = store.now();
        if (number(timer, "generation") != stage.generation() || number(timer, "policy_version") != stage.context().policy().version()
                || !time(timer, "due_at").isBefore(now) || !Set.of("WARNING", "BREACH").contains(timer.get("kind"))) throw changed();
        if (lease != null) {
            if (!"CLAIMED".equals(timer.get("status")) || number(timer, "lease_epoch") != lease.epoch() || !lease.owner().equals(timer.get("lease_owner"))
                    || !lease.until().equals(time(timer, "lease_until")) || !lease.until().isAfter(now) || lease.generation() != stage.generation()
                    || lease.policyVersion() != number(timer, "policy_version") || !lease.kind().equals(timer.get("kind")) || timer.get("event_id") != null) throw changed();
        } else if (!"COMPLETED".equals(timer.get("status")) || timer.get("lease_owner") != null || timer.get("lease_until") != null
                || !uuid(notification.request(), "eventId").equals(timer.get("event_id"))) throw changed();
        var roots = roots(p, request, stage, now);
        store.verify(stage.context(), tenant, requestId, stage.definition());
        var definition = ApprovalWorkflowQuorumDefinition.compile(stage.definition());
        var selected = definition.stages().stream().filter(value -> value.key().equals(stage.key())).findFirst().orElseThrow(SystemSlaJson::changed);
        var snapshot = stage.snapshot(); var context = stage.context();
        if (!selected.candidateRole().equals(snapshot.candidateRole()) || !selected.quorum().equals(snapshot.rule()) || !snapshot.pins().equals(context.pins())
                || !snapshot.requestId().equals(requestId) || !snapshot.stepId().equals(stepId) || snapshot.generation() != stage.generation()
                || snapshot.requesterUserId() != context.requesterUserId() || !snapshot.requesterPersonPublicId().equals(context.requesterPersonId())
                || snapshot.payloadRevision() != context.payloadRevision() || !snapshot.payloadSha256().equals(context.payloadSha256())) throw changed();
        var policy = store.policy(tenant, requestId, true);
        var reviews = reviewed(p, policy, now);
        var seatData = seats(p, stage); var seats = seatData.values(); var actionable = new HashSet<>(seatData.actionable());
        JsonNode event = NullNode.instance; JsonNode audience = json.tree(seats); Object outboxVector = List.of(); JsonNode witness=NullNode.instance;
        if (notification != null) {
            p.addValue("event", uuid(notification.request(), "eventId"));
            var events = store.jdbc.queryForList("SELECT integration.payload::text AS raw_payload,integration.event_type,owner.event_data::text AS owner_data,owner.actor_type "
                    + "FROM apr_integration_outbox integration JOIN apr_request_events owner ON owner.tenant_id=integration.tenant_id AND owner.request_id=integration.request_id AND owner.event_id=integration.event_id "
                    + "WHERE integration.tenant_id=:tenant AND integration.request_id=:request AND integration.event_id=:event FOR SHARE OF integration,owner", p);
            if (events.size() != 1 || !"SYSTEM".equals(events.getFirst().get("actor_type"))) throw changed();
            var record = events.getFirst(); String raw = (String) record.get("raw_payload"); var envelope = json.parse(raw.getBytes(StandardCharsets.UTF_8));
            keys(envelope, ENVELOPE); var body = envelope.get("payload"); keys(body, PAYLOAD);
            var input = notification.request(); String type = text(input, "eventType", 40);
            store.jdbc.queryForObject("SELECT set_config('dwp.approval.system_sla.tenant',:tenant::text,true)",p,String.class);
            var witnesses=store.jdbc.queryForList("SELECT * FROM apr_system_sla_source_witnesses WHERE tenant_id=:tenant AND request_id=:request AND event_id=:event FOR SHARE",p);
            if (witnesses.size()!=1) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"UNKNOWN_ORIGINAL_SYSTEM_SLA_SOURCE: historical witnesses are never repaired on reads.");
            var captured=witnesses.getFirst();
            if (!request.get("data_classification").equals(captured.get("original_classification")) || !stepId.equals(captured.get("step_id"))
                    || !timerId.equals(captured.get("timer_id")) || number(captured,"generation")!=stage.generation()
                    || number(captured,"original_request_version")!=integer(body,"requestVersion",false)
                    || number(captured,"original_stage_version")!=integer(body,"stageVersion",false)
                    || !captured.get("recipient_snapshot_sha256").equals(hash(input,"recipientSnapshotSha256"))
                    || !captured.get("source_pins_sha256").equals(hash(input,"sourcePinsSha256"))) throw changed();
            var witnessProjection=new LinkedHashMap<>(captured);
            if (!(captured.get("source_body") instanceof byte[] sourceBytes) || sourceBytes.length>SystemSlaSourceProtocol.BODY_LIMIT) throw changed();
            witnessProjection.put("source_body",Base64.getEncoder().encodeToString(sourceBytes));
            witnessProjection.put("created_at",time(captured,"created_at").toString()); witnessProjection.put("expires_at",time(captured,"expires_at").toString());
            witness=json.tree(witnessProjection); ((com.fasterxml.jackson.databind.node.ObjectNode) witness).put("event_authority_revision",text(body,"authorityRevision",200));
            if (!type.equals("WARNING".equals(timer.get("kind")) ? "Approval.Quorum.SlaWarning" : "Approval.Quorum.SlaBreached")
                    || Instant.parse(text(body, "occurredAt", 40)).isAfter(now)) throw changed();
            if (!"1.0".equals(text(envelope, "specVersion", 8)) || !type.equals(record.get("event_type")) || !type.equals(text(envelope, "eventType", 40))
                    || integer(envelope, "tenantId", true) != tenant || !uuid(envelope, "requestId").equals(requestId) || !envelope.get("correlationId").isTextual()
                    || !envelope.get("correlationId").textValue().isEmpty() || !body.equals(json.parse(((String) record.get("owner_data")).getBytes(StandardCharsets.UTF_8)))
                    || !hash(input, "originalEnvelopeSha256").equals(sha(raw.getBytes(StandardCharsets.UTF_8)))
                    || !hash(input, "canonicalEnvelopeSha256").equals(json.digest(envelope))) throw changed();
            var originalIds = body.get("recipientUserIds"); audience = body.get("recipientSeats");
            if (!input.get("requestedRecipientUserIds").equals(originalIds) || !audience.isArray() || audience.isEmpty() || audience.size() > 1000
                    || !hash(input, "recipientSnapshotSha256").equals(hash(body, "recipientSnapshotSha256"))
                    || !hash(input, "recipientSnapshotSha256").equals(json.digest(Map.of("recipientUserIds", originalIds, "recipientSeats", audience)))) throw changed();
            if (audience.size()!=originalIds.size()) throw changed();
            for (int index=0;index<audience.size();index++) {
                keys(audience.get(index), Set.of("userId", "personPublicId", "taskId", "taskVersion"));
                if (integer(audience.get(index),"userId",true)!=originalIds.get(index).longValue()) throw changed();
            }
            var actual = json.tree(seats);
            for (var seat : audience) {
                var matches = java.util.stream.StreamSupport.stream(actual.spliterator(),false).filter(value -> integer(value,"userId",true)==integer(seat,"userId",true)
                        && uuid(value,"personPublicId").equals(uuid(seat,"personPublicId")) && uuid(value,"taskId").equals(uuid(seat,"taskId"))).toList();
                if (matches.size()!=1 || integer(seat,"taskVersion",false)>integer(matches.getFirst(),"taskVersion",false)) throw changed();
                if (integer(seat,"taskVersion",false)!=integer(matches.getFirst(),"taskVersion",false)) actionable.remove(uuid(seat,"taskId"));
            }
            long originalStageVersion = integer(body,"stageVersion",false); if (originalStageVersion>stage.version()) throw changed();
            long originalRequestVersion = integer(body,"requestVersion",false);
            var nativeVotes = requestAdvance(p,request,stage,seats,originalRequestVersion,originalStageVersion,Instant.parse(text(body,"occurredAt",40)),now);
            var currentPayload = (com.fasterxml.jackson.databind.node.ObjectNode) json.tree(ApprovalWorkflowQuorumSlaEvent.payload(store, requestId, timerId, number(timer, "lease_epoch"), stage,
                    java.util.stream.StreamSupport.stream(originalIds.spliterator(), false).map(JsonNode::longValue).toList(), text(body, "authorityRevision", 200), Instant.parse(text(body, "occurredAt", 40))));
            // Compare the original immutable event, while current versions/states remain sealed in the private native vector.
            currentPayload.put("stageVersion",originalStageVersion); currentPayload.set("recipientSeats",audience.deepCopy());
            currentPayload.put("requestVersion",originalRequestVersion);
            currentPayload.put("recipientSnapshotSha256",json.digest(Map.of("recipientUserIds",originalIds,"recipientSeats",audience)));
            if (!json.digest(currentPayload).equals(json.digest(body)) || !hash(input, "sourcePinsSha256").equals(json.digest(eventPins(body)))) throw changed();
            event = json.tree(Map.of("eventId", uuid(input, "eventId"), "eventType", type, "originalEnvelopeSha256", hash(input, "originalEnvelopeSha256"), "canonicalEnvelopeSha256", hash(input, "canonicalEnvelopeSha256")));
            outboxVector = Map.of("rawPayload", raw, "ownerData", record.get("owner_data"), "nativeVotes",nativeVotes,"originalWitness",witness);
        }
        var pins = context.pins();
        var source = new LinkedHashMap<String, Object>();
        source.put("request", Map.of("requestId", requestId, "requestVersion", request.get("version"), "requesterUserId", context.requesterUserId(), "requesterPersonPublicId", context.requesterPersonId(), "dataClassification", request.get("data_classification"), "resourceSetKey", request.get("management_resource_set_key")));
        source.put("workflow", Map.of("workflowVersionId", pins.workflowVersionId(), "workflowVersion", pins.workflowVersion(), "definitionSha256", pins.workflowDefinitionSha256(), "definition", json.parse(definition.canonicalJson().getBytes(StandardCharsets.UTF_8))));
        source.put("form", Map.of("formVersionId", context.formVersionId(), "schemaSha256", pins.formSchemaSha256()));
        source.put("payload", Map.of("revision", context.payloadRevision(), "sha256", context.payloadSha256()));
        source.put("policy", Map.of("version", policy.version(), "sha256", policy.sha256(), "references", policy.references(), "reviewedHistory", reviews));
        source.put("stage", Map.of("stepId", stepId, "generation", stage.generation(), "version", stage.version(), "stageKey", stage.key(), "candidateRole", selected.candidateRole(), "frozenPoolSha256", json.digest(stage.snapshot().candidates())));
        var timerSource = new LinkedHashMap<String, Object>();
        timerSource.put("timerId", timerId); timerSource.put("version", timer.get("version")); timerSource.put("kind", timer.get("kind")); timerSource.put("dueAt", time(timer, "due_at").toString());
        timerSource.put("leaseEpoch", timer.get("lease_epoch")); timerSource.put("leaseOwner", timer.get("lease_owner")); timerSource.put("leaseUntil", timer.get("lease_until") == null ? null : time(timer, "lease_until").toString()); timerSource.put("policyVersion", timer.get("policy_version"));
        source.put("timer", timerSource); source.put("event", event);
        String operation = lease == null ? "DELIVER" : "PRODUCE";
        String digest = json.digest(Map.of("tenantId", tenant, "operation", operation, "source", source, "audience", audience));
        Instant until = now.plusSeconds(30);
        for (Instant bound : List.of(lease == null ? notification.expiresAt() : lease.until(), (Instant) roots.get("deadline"))) if (bound.isBefore(until)) until = bound;
        until = Instant.ofEpochSecond(until.getEpochSecond()); if (!until.isAfter(now)) throw changed();
        String vector = json.digest(Map.of("tenant", tenants.getFirst(), "request", request, "step", steps.getFirst(), "stage", stageRows.getFirst(), "timer", timer, "roots", roots.get("vector"), "reviews", reviews, "tasks", seatData.vector(), "outbox", outboxVector));
        return new Seal(json.tree(Map.of("tenantId", tenant, "operation", operation, "source", source, "audience", audience, "sourceDigest", digest, "authorityValidUntil", until.toString())), vector, until, lease, notification, actionable,witness,
                store.jdbc.getJdbcTemplate().getDataSource(),Objects.requireNonNull(store.jdbc.queryForObject("SELECT txid_current()",p,Long.class)));
    }
    private List<Map<String,Object>> requestAdvance(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p,Map<String,Object> request,
            ApprovalWorkflowQuorumRuntimeStore.StageRow stage,List<Map<String,Object>> seats,long originalRequest,long originalStage,Instant occurred,Instant now) {
        long current = number(request,"version"), delta = current-originalRequest;
        if (delta<0 || delta>1000 || stage.version()-originalStage!=delta) throw changed();
        p.addValue("generation",stage.generation()).addValue("originalStage",originalStage).addValue("currentStage",stage.version());
        var rows = store.jdbc.queryForList("SELECT vote_id,stage_version,task_id,principal_user_id,principal_person_id,actor_user_id,actor_person_id,decision,evidence::text AS evidence,accepted_at "
                + "FROM apr_quorum_votes WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation "
                + "AND stage_version>:originalStage AND stage_version<=:currentStage ORDER BY stage_version FOR SHARE",p);
        if (rows.size()!=delta) throw changed();
        for (int index=0;index<rows.size();index++) {
            var row = rows.get(index); var vote = store.read((String) row.get("evidence"),ApprovalWorkflowQuorum.Vote.class);
            if (number(row,"stage_version")!=originalStage+index+1 || vote.stageVersion()!=number(row,"stage_version") || !vote.pins().equals(stage.context().pins())
                    || !vote.requestId().equals(request.get("request_id")) || !vote.stepId().equals(stage.stepId()) || vote.generation()!=stage.generation()
                    || vote.decision()!=ApprovalWorkflowQuorum.Decision.APPROVE || !"APPROVE".equals(row.get("decision"))
                    || vote.principalUserId()!=number(row,"principal_user_id") || !vote.principalPersonPublicId().equals(row.get("principal_person_id"))
                    || vote.actorUserId()!=number(row,"actor_user_id") || !vote.actorPersonPublicId().equals(row.get("actor_person_id"))
                    || vote.payloadRevision()!=stage.context().payloadRevision() || !vote.payloadSha256().equals(stage.context().payloadSha256())
                    || !vote.acceptedAt().equals(time(row,"accepted_at")) || vote.acceptedAt().isBefore(occurred) || vote.acceptedAt().isAfter(now)
                    || seats.stream().noneMatch(seat -> ((Number) seat.get("userId")).longValue()==vote.principalUserId()
                        && vote.principalPersonPublicId().toString().equals(seat.get("personPublicId")) && row.get("task_id").toString().equals(seat.get("taskId")))) throw changed();
        }
        return List.copyOf(rows);
    }
    private Map<String, Object> roots(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p, Map<String, Object> request,
            ApprovalWorkflowQuorumRuntimeStore.StageRow stage, Instant now) {
        var rows = store.jdbc.queryForList("""
                SELECT definition.lifecycle_state AS workflow_state,definition.management_resource_set_key,definition.version AS workflow_revision,
                       definition.current_version,definition.workflow_id,version.version_number,version.definition_sha256,
                       version.effective_from,version.effective_to,form.form_id,form.lifecycle_state AS form_state,form.version AS form_revision,
                       form.management_resource_set_key AS form_resource,category.category_id,category.lifecycle_state AS category_state,
                       category.management_resource_set_key AS category_resource,category.version AS category_revision,workspace.workspace_version,
                       material.provenance,material.schema_sha256 AS material_schema,material.metadata_payload::text,material.route_payload::text,material.material_sha256,material.captured_at
                  FROM apr_requests request JOIN apr_workflow_versions version ON version.tenant_id=request.tenant_id AND version.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions definition ON definition.tenant_id=version.tenant_id AND definition.workflow_id=version.workflow_id
                  JOIN apr_form_versions immutable ON immutable.tenant_id=request.tenant_id AND immutable.form_version_id=request.form_version_id AND immutable.lifecycle_state='PUBLISHED'
                  JOIN apr_forms form ON form.tenant_id=immutable.tenant_id AND form.form_id=immutable.form_id
                  LEFT JOIN apr_form_version_material material ON material.tenant_id=immutable.tenant_id AND material.form_id=immutable.form_id AND material.form_version_id=immutable.form_version_id
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=CASE WHEN material.provenance='PUBLISH_SNAPSHOT'
                    AND (material.metadata_payload->>'categoryId') ~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
                    THEN (material.metadata_payload->>'categoryId')::uuid ELSE form.category_id END
                  LEFT JOIN apr_form_workspaces workspace ON workspace.tenant_id=form.tenant_id AND workspace.form_id=form.form_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request FOR SHARE OF definition,version,immutable,form,category
                """, p);
        if (rows.size() != 1) throw changed(); var row = rows.getFirst();
        if (!Set.of("DRAFT", "PUBLISHED").contains(row.get("workflow_state")) || !Set.of("DRAFT", "PUBLISHED").contains(row.get("form_state"))
                || !"ACTIVE".equals(row.get("category_state")) || !Objects.equals(row.get("management_resource_set_key"), request.get("management_resource_set_key"))
                || !Objects.equals(row.get("form_resource"), request.get("management_resource_set_key")) || !Objects.equals(row.get("category_resource"), request.get("management_resource_set_key"))
                || (row.get("effective_from") != null && time(row, "effective_from").isAfter(now)) || (row.get("effective_to") != null && !time(row, "effective_to").isAfter(now))) throw changed();
        if (!stage.context().pins().workflowVersionId().equals(request.get("workflow_version_id")) || !stage.context().formVersionId().equals(request.get("form_version_id"))) throw changed();
        String provenance = (String) row.get("provenance");
        if (provenance != null) {
            if (!Set.of("PUBLISH_SNAPSHOT", "LEGACY_CAPTURE_TIME").contains(provenance) || row.get("captured_at") == null) throw changed();
            var codec = new com.dwp.services.approval.forms.ApprovalFormMaterialCodec(store.json, (com.dwp.services.approval.forms.ApprovalFormLegacySchemaValidator) null);
            var metadata = codec.object((String) row.get("metadata_payload")); var route = codec.object((String) row.get("route_payload"));
            if (!stage.context().pins().formSchemaSha256().equals(((String) row.get("material_schema")).trim())
                    || !codec.material(stage.context().pins().formSchemaSha256(), metadata, route).equals(((String) row.get("material_sha256")).trim())) throw changed();
            if (provenance.equals("PUBLISH_SNAPSHOT")) {
                if (!route.keySet().equals(Set.of("workflowId", "workflowVersionId", "workflowRevision", "workflowVersionNumber", "definitionSha256", "dataClassification", "slaMinutes", "effectiveFrom", "effectiveTo"))) throw changed();
                var captured = codec.project(route, com.dwp.services.approval.forms.ApprovalFormPublishedRoutePin.WorkflowRoute.class);
                if (!captured.workflowId().equals(row.get("workflow_id")) || !captured.workflowVersionId().equals(request.get("workflow_version_id"))
                        || captured.workflowVersionNumber() != number(row, "version_number") || !captured.definitionSha256().equals(stage.context().pins().workflowDefinitionSha256())
                        || !captured.dataClassification().equals(request.get("data_classification")) || !Objects.equals(captured.effectiveFrom(), nullableTime(row, "effective_from"))
                        || !Objects.equals(captured.effectiveTo(), nullableTime(row, "effective_to")) || row.get("workspace_version") == null
                        || !row.get("category_id").toString().equals(metadata.get("categoryId"))) throw changed();
            }
        }
        Instant deadline = row.get("effective_to") == null ? Instant.MAX : time(row, "effective_to");
        Object bindingVector = List.of();
        if (!"PUBLISH_SNAPSHOT".equals(provenance)) {
            p.addValue("form", row.get("form_id")).addValue("workflow", row.get("workflow_id"));
            var bindings = store.jdbc.queryForList("SELECT * FROM apr_form_workflow_bindings WHERE tenant_id=:tenant AND form_id=:form AND workflow_id=:workflow AND lifecycle_state='ACTIVE' FOR SHARE", p);
            if (bindings.size() != 1) throw changed(); var binding = bindings.getFirst();
            if ((binding.get("effective_from") != null && time(binding, "effective_from").isAfter(now)) || (binding.get("effective_to") != null && !time(binding, "effective_to").isAfter(now))) throw changed();
            if (binding.get("effective_to") != null && time(binding, "effective_to").isBefore(deadline)) deadline = time(binding, "effective_to"); bindingVector = binding;
        }
        return Map.of("vector", Map.of("root", row, "legacyBinding", bindingVector), "deadline", deadline);
    }
    private List<Map<String, Object>> reviewed(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p,
            ApprovalWorkflowQuorumRuntimeStore.Policy policy, Instant now) {
        var result = new ArrayList<Map<String, Object>>();
        for (var reference : policy.references()) {
            p.addValue("policy", UUID.fromString((String) reference.get("policyId"))).addValue("version", ((Number) reference.get("rowVersion")).longValue() + 1);
            var rows = store.jdbc.queryForList("SELECT history.*,capture.source_kind FROM apr_policy_rule_versions history "
                    + "LEFT JOIN apr_policy_version_source_records capture ON capture.tenant_id=history.tenant_id AND capture.policy_id=history.policy_id AND capture.policy_version_id=history.policy_version_id "
                    + "WHERE history.tenant_id=:tenant AND history.policy_id=:policy AND history.version_number=:version FOR SHARE OF history", p);
            if (rows.size() != 1) throw notReviewed(); var row = rows.getFirst();
            if (row.get("source_kind") != null || row.get("submitted_by") == null || row.get("published_by") == null || row.get("submitted_by").equals(row.get("published_by"))
                    || row.get("submitted_at") == null || row.get("published_at") == null || time(row, "published_at").isBefore(time(row, "submitted_at"))
                    || time(row, "published_at").isAfter(now) || !"ACTIVE".equals(row.get("lifecycle_state")) || !reference.get("enforcement").equals(row.get("enforcement_mode"))
                    || !json.digest(reference.get("rule")).equals(json.digest(json.parse(row.get("rule_payload").toString().getBytes(StandardCharsets.UTF_8))))) throw notReviewed();
            result.add(Map.of("policyVersionId", row.get("policy_version_id"), "policyId", row.get("policy_id"), "versionNumber", row.get("version_number"), "makerId", row.get("submitted_by"), "publisherId", row.get("published_by"),
                    "submittedAt", time(row, "submitted_at").toString(), "publishedAt", time(row, "published_at").toString(), "ruleSha256", json.digest(reference.get("rule")), "provenance", "REVIEWED_PUBLISH"));
        }
        return List.copyOf(result);
    }
    private record Seats(List<Map<String, Object>> values, List<Map<String, Object>> vector, Set<UUID> actionable) { }
    private Seats seats(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p, ApprovalWorkflowQuorumRuntimeStore.StageRow stage) {
        p.addValue("generation", stage.generation());
        var rows = store.jdbc.queryForList("SELECT candidate.principal_user_id,candidate.principal_person_id,candidate.task_id,task.version,task.assignee_user_id,task.assignee_person_public_id,task.candidate_role,task.status "
                + "FROM apr_quorum_candidates candidate JOIN apr_tasks task ON task.tenant_id=candidate.tenant_id AND task.request_id=candidate.request_id AND task.step_id=candidate.step_id AND task.task_id=candidate.task_id "
                + "WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.step_id=:step AND candidate.generation=:generation ORDER BY candidate.principal_user_id FOR SHARE OF candidate,task", p);
        if (rows.size() != stage.snapshot().candidates().size()) throw changed(); var output = new ArrayList<Map<String, Object>>(); var actionable = new HashSet<UUID>();
        var frozen = stage.snapshot().candidates().stream().sorted(Comparator.comparingLong(ApprovalWorkflowQuorum.Candidate::userId)).toList();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index); var candidate = frozen.get(index);
            if (candidate.userId() != number(row, "principal_user_id") || !candidate.personPublicId().equals(row.get("principal_person_id"))
                    || candidate.userId() != number(row, "assignee_user_id") || !candidate.personPublicId().equals(row.get("assignee_person_public_id"))
                    || !stage.snapshot().candidateRole().equals(row.get("candidate_role"))) throw changed();
            output.add(Map.of("userId", candidate.userId(), "personPublicId", candidate.personPublicId().toString(), "taskId", row.get("task_id").toString(), "taskVersion", row.get("version")));
            if (Set.of("PENDING", "CLAIMED").contains(row.get("status"))) actionable.add((UUID) row.get("task_id"));
        }
        return new Seats(List.copyOf(output), List.copyOf(rows), Set.copyOf(actionable));
    }
    private Map<String, JsonNode> eventPins(JsonNode body) {
        var pins = new LinkedHashMap<String, JsonNode>();
        for (String key : List.of("requestTitle", "managementResourceSetKey", "stageKey", "authorityRevision", "stepId", "workflowVersionId", "formVersionId", "timerId", "workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256", "requestVersion", "stageVersion", "generation", "workflowVersion", "payloadRevision", "policyVersion", "leaseEpoch")) pins.put(key, body.get(key));
        return pins;
    }
    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key); if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 9007199254740991L) throw changed(); return ((Number) value).longValue();
    }
    private static Instant time(Map<String, Object> row, String key) { if (!(row.get(key) instanceof Timestamp value)) throw changed(); return value.toInstant(); }
    private static Instant nullableTime(Map<String, Object> row, String key) { return row.get(key) == null ? null : time(row, key); }
    private static void mandatory() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable(); }
    private static BaseException notReviewed() { return new BaseException(ErrorCode.FORBIDDEN, "DENY_NOT_REVIEWED: immutable maker-checker policy publication is required."); }
}
