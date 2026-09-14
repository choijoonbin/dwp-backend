package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.systemslaauthority.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual DB owner seals. Fixture review rows and the old runtime's fixture authority are not deployed Auth grants. */
@Testcontainers
class ApprovalSystemSlaNativeSourcePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp-owner", "cicero-native-system-sla");
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SystemSlaJson json = new SystemSlaJson(mapper);
    static final RSAKey TRANSPORT = key("notification-sla-recipient-transport:pg"), RESPONSE = key("approval-sla-recipient-authority:pg"), OTHER = key("foreign:pg");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalSystemSlaNativeSource source;
    @BeforeEach void setup() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(PG);
        source = new ApprovalSystemSlaNativeSource(new NamedParameterJdbcTemplate(f.jdbc), mapper);
    }
    @Test void claimedLeaseProducesOnlyPrivateCurrentDbPinsAndCompleteFrozenTaskAudience() {
        var lease = ready(true); String before = database();
        var seal = f.tx.execute(status -> source.produce(lease));
        assertThat(seal.delivery()).isFalse(); assertThat(seal.bindings().size()).isEqualTo(6);
        assertThat(seal.bindings().get("operation").asText()).isEqualTo("PRODUCE");
        assertThat(seal.bindings().at("/source/stage/version").longValue()).isEqualTo(f.jdbc.queryForObject("SELECT version FROM apr_quorum_stage_runtime WHERE step_id=?", Long.class, lease.stepId()));
        assertThat(seal.bindings().at("/source/workflow/workflowVersionId").asText()).isEqualTo(f.workflowVersion.toString());
        assertThat(seal.bindings().at("/source/policy/reviewedHistory").size()).isEqualTo(3);
        assertThat(seal.bindings().get("audience").size()).isEqualTo(3);
        for (var seat : seal.bindings().get("audience")) assertThat(seal.taskEligible(UUID.fromString(seat.get("taskId").asText()))).isTrue();
        f.tx.executeWithoutResult(status -> source.unchanged(seal)); assertThat(database()).isEqualTo(before);
        ((com.fasterxml.jackson.databind.node.ObjectNode) seal.bindings()).put("tenantId", 777);
        assertThat(seal.bindings().get("tenantId").longValue()).isEqualTo(TENANT);
        assertThatThrownBy(() -> source.produce(lease)).isInstanceOf(BaseException.class);
    }
    @Test void unreviewedLegacyBaselineIsDeniedWithoutHealingSourceJournalOrBusinessWrites() {
        var lease = ready(false); String before = database();
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class).hasMessageContaining("DENY_NOT_REVIEWED");
        assertThat(database()).isEqualTo(before);
    }
    @Test void legacyCaptureProvenanceCannotBeUpgradedByPopulatedDifferentMakerAndPublisher() {
        var lease = ready(true);
        f.jdbc.update("INSERT INTO apr_policy_version_source_records(policy_version_id,tenant_id,policy_id,source_kind,captured_at) "
                + "SELECT policy_version_id,tenant_id,policy_id,'LEGACY_CAPTURE_TIME',clock_timestamp() FROM apr_policy_rule_versions WHERE tenant_id=42 AND version_number=2");
        String before = database();
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class).hasMessageContaining("DENY_NOT_REVIEWED");
        assertThat(database()).isEqualTo(before);
    }
    @Test void currentLeaseEpochAndNativeTaskStatusAreRecheckedWithoutChangingFrozenSeats() {
        var lease = ready(true); var initial = f.tx.execute(status -> source.produce(lease));
        UUID task = UUID.fromString(initial.bindings().get("audience").get(0).get("taskId").asText());
        f.runtime.vote(f.command("FINANCE", 101, 101, ApprovalWorkflowQuorum.Decision.APPROVE));
        var updated = f.tx.execute(status -> source.produce(lease));
        assertThat(updated.bindings().get("audience").size()).isEqualTo(3); assertThat(updated.taskEligible(task)).isFalse();
        assertThat(updated.nativeVectorSha256()).isNotEqualTo(initial.nativeVectorSha256());
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> source.unchanged(initial))).isInstanceOf(BaseException.class);
        f.jdbc.update("UPDATE apr_quorum_sla_timers SET lease_epoch=lease_epoch+1,version=version+1 WHERE timer_id=?", lease.timerId());
        String before = database();
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class);
        assertThat(database()).isEqualTo(before);
    }
    @Test void changedCanonicalTaskOwnerIsNotReplacedWithADelegateOrAnotherFrozenPrincipal() {
        var lease = ready(true); UUID task = f.jdbc.queryForObject("SELECT task_id FROM apr_quorum_candidates WHERE request_id=? ORDER BY principal_user_id LIMIT 1", UUID.class, f.request);
        f.jdbc.update("UPDATE apr_tasks SET assignee_user_id=999,assignee_person_public_id=?,version=version+1 WHERE task_id=?", person(999), task);
        String before = database(); assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class); assertThat(database()).isEqualTo(before);
    }
    @Test void skippedNativeStepCannotBorrowAnOtherwiseUnchangedInProgressRuntimeStage() {
        var lease = ready(true); f.jdbc.update("UPDATE apr_steps SET status='SKIPPED',version=version+1 WHERE step_id=?", lease.stepId());
        String before = database(); assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class); assertThat(database()).isEqualTo(before);
    }
    @Test void suspendedThenReactivatedNativeTenantHasANewFullNativeAuthorityVector() {
        var lease = ready(true); var original = f.tx.execute(status -> source.produce(lease));
        f.jdbc.update("UPDATE apr_tenants SET lifecycle_state='SUSPENDED',updated_at=clock_timestamp() WHERE tenant_id=42");
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class);
        f.jdbc.update("UPDATE apr_tenants SET lifecycle_state='ACTIVE',updated_at=clock_timestamp() WHERE tenant_id=42");
        var current = f.tx.execute(status -> source.produce(lease)); assertThat(current.nativeVectorSha256()).isNotEqualTo(original.nativeVectorSha256());
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> source.unchanged(original))).isInstanceOf(BaseException.class);
    }
    @Test void deliveryBindsActualOutboxUtf8BytesOriginalEventAndCurrentStageWithoutProducerProofReuse() {
        var lease = ready(true); assertThat(f.sla.finish(lease)).isTrue();
        var request = notificationRequest(); var verified = notification(request); String before = database();
        var seal = f.tx.execute(status -> source.deliver(verified));
        assertThat(seal.delivery()).isTrue(); assertThat(seal.bindings().get("operation").asText()).isEqualTo("DELIVER");
        assertThat(seal.bindings().at("/source/stage/version").longValue()).isEqualTo(f.jdbc.queryForObject("SELECT version FROM apr_quorum_stage_runtime WHERE step_id=?", Long.class, lease.stepId()));
        assertThat(seal.bindings().at("/source/timer/leaseOwner").isNull()).isTrue();
        assertThat(seal.bindings().at("/source/event/eventId").asText()).isEqualTo(request.get("eventId"));
        assertThat(seal.bindings().get("audience").size()).isEqualTo(3);
        f.tx.executeWithoutResult(status -> source.unchanged(seal)); assertThat(database()).isEqualTo(before);
    }
    @Test void aTaskCompletedAfterOriginalOutboxRetainsItsFrozenSeatButCannotReceiveAnEscalation() {
        var lease = ready(true); assertThat(f.sla.finish(lease)).isTrue(); var input = notificationRequest();
        var original = f.tx.execute(status -> source.deliver(notification(input)));
        f.runtime.vote(f.command("FINANCE",101,101,ApprovalWorkflowQuorum.Decision.APPROVE));
        String before = database(); var seal = f.tx.execute(status -> source.deliver(notification(input)));
        assertThat(seal.bindings().get("audience")).hasSize(3);
        var seat = seal.bindings().get("audience").get(0); assertThat(seat.get("taskVersion").longValue()).isEqualTo(0);
        assertThat(seal.taskEligible(UUID.fromString(seat.get("taskId").asText()))).isFalse();
        for (int index=1;index<3;index++) assertThat(seal.taskEligible(UUID.fromString(seal.bindings().get("audience").get(index).get("taskId").asText()))).isTrue();
        assertThat(seal.bindings().get("audience")).isEqualTo(original.bindings().get("audience"));
        assertThat(seal.nativeVectorSha256()).isNotEqualTo(original.nativeVectorSha256());
        assertThat(seal.bindings().at("/source/stage/version").longValue()).isGreaterThan(original.bindings().at("/source/stage/version").longValue());
        assertThat(notificationRequest()).isEqualTo(input);
        f.tx.executeWithoutResult(status -> source.unchanged(seal));
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> source.unchanged(original))).isInstanceOf(BaseException.class);
        assertThat(database()).isEqualTo(before);
    }
    @Test void taskVersionAdvancedWhileStillClaimedIsIneligibleAndCurrentVectorDetectsFurtherNativeChanges() {
        var lease = ready(true); assertThat(f.sla.finish(lease)).isTrue(); var input = notificationRequest();
        UUID task = f.jdbc.queryForObject("SELECT task_id FROM apr_quorum_candidates WHERE request_id=? ORDER BY principal_user_id LIMIT 1",UUID.class,f.request);
        assertThat(f.jdbc.update("UPDATE apr_tasks SET version=version+1 WHERE task_id=? AND status='CLAIMED'",task)).isEqualTo(1);
        String before = database(); var seal = f.tx.execute(status -> source.deliver(notification(input)));
        assertThat(seal.taskEligible(task)).isFalse(); assertThat(seal.bindings().at("/audience/0/taskVersion").longValue()).isZero();
        assertThat(notificationRequest()).isEqualTo(input); assertThat(database()).isEqualTo(before);
        f.jdbc.update("UPDATE apr_tasks SET version=version+1 WHERE task_id=?",task);
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> source.unchanged(seal))).isInstanceOf(BaseException.class);
    }
    @Test void originalFutureTaskOrStageAndWrongTaskIdOrGenerationNativeEventsAreDeniedWithoutRewrite() {
        for (var mutation : List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                value -> ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/recipientSeats/0")).put("taskVersion",1),
                value -> value.put("stageVersion",value.get("stageVersion").longValue()+1),
                value -> value.put("requestVersion",value.get("requestVersion").longValue()+1),
                value -> ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/recipientSeats/0")).put("taskId",UUID.randomUUID().toString()),
                value -> value.put("generation",value.get("generation").longValue()+1))) {
            setup(); var lease = ready(true); malformedOriginalEvent(lease,mutation); var input = notificationRequest(); String before = database();
            assertThatThrownBy(() -> f.tx.execute(status -> source.deliver(notification(input)))).isInstanceOf(BaseException.class);
            assertThat(notificationRequest()).isEqualTo(input); assertThat(database()).isEqualTo(before);
        }
    }
    @Test void deliveryCannotTreatUnprovenRequestVersionOrPayloadOrClassificationTamperAsANativeVote() {
        for (String sql : List.of("UPDATE apr_requests SET version=version+1 WHERE request_id=?",
                "UPDATE apr_request_payloads SET payload_sha256='"+"b".repeat(64)+"' WHERE request_id=?",
                "UPDATE apr_requests SET data_classification='RESTRICTED' WHERE request_id=?")) {
            setup(); var lease = ready(true); managed(route()); assertThat(f.sla.finish(lease)).isTrue(); var input = notificationRequest();
            assertThat(f.jdbc.update(sql,f.request)).isEqualTo(1); String before = database();
            assertThatThrownBy(() -> f.tx.execute(status -> source.deliver(notification(input)))).as(sql).isInstanceOf(BaseException.class);
            assertThat(database()).isEqualTo(before); assertThat(notificationRequest()).isEqualTo(input);
        }
    }
    @Test void nonLiveRetentionRequestDeniesDeliveryBeforeReadingItsOriginalEvent() {
        var lease = ready(true); assertThat(f.sla.finish(lease)).isTrue(); var input = notificationRequest();
        f.jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'PREPARED')",f.request); String before = database();
        assertThatThrownBy(() -> f.tx.execute(status -> source.deliver(notification(input)))).isInstanceOfSatisfying(BaseException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.NOT_FOUND));
        assertThat(database()).isEqualTo(before);
    }
    @Test void deliveryRejectsCallerSubsetAlternateEventAndRehashedWrongOwnerPinsWithZeroBusinessWrites() {
        var lease = ready(true); assertThat(f.sla.finish(lease)).isTrue(); var original = notificationRequest(); String before = database();
        for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                value -> value.put("requestedRecipientUserIds", List.of(101L)), value -> value.put("eventId", UUID.randomUUID().toString()),
                value -> value.put("requestId", UUID.randomUUID().toString()), value -> value.put("canonicalEnvelopeSha256", "0".repeat(64)),
                value -> value.put("sourcePinsSha256", "0".repeat(64)), value -> value.put("tenantId", 43L),
                value -> value.put("originalEnvelopeSha256","0".repeat(64)), value -> value.put("recipientSnapshotSha256","0".repeat(64)),
                value -> value.put("eventType",original.get("eventType").equals("Approval.Quorum.SlaBreached") ? "Approval.Quorum.SlaWarning" : "Approval.Quorum.SlaBreached"))) {
            var changed = new LinkedHashMap<>(original); mutation.accept(changed); var verified = notification(changed);
            assertThatThrownBy(() -> f.tx.execute(status -> source.deliver(verified))).isInstanceOf(BaseException.class);
            assertThat(database()).isEqualTo(before);
        }
    }
    void malformedOriginalEvent(ApprovalWorkflowQuorumSlaRuntime.Lease lease,java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) {
        f.tx.executeWithoutResult(status -> {
            var named = new NamedParameterJdbcTemplate(f.jdbc); var store = new ApprovalWorkflowQuorumRuntimeStore(named,mapper);
            var stage = store.stages(TENANT,f.request,true).getFirst();
            var data = (com.fasterxml.jackson.databind.node.ObjectNode) json.tree(ApprovalWorkflowQuorumSlaEvent.payload(store,f.request,lease.timerId(),lease.epoch(),stage,List.of(101L,102L,103L),"fixture-malformed-original",store.now()));
            mutation.accept(data); data.put("recipientSnapshotSha256",json.digest(Map.of("recipientUserIds",data.get("recipientUserIds"),"recipientSeats",data.get("recipientSeats"))));
            var audit = new com.dwp.core.audit.AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test");
            UUID event = new ApprovalWorkflowQuorumEvidence(store,audit).append(TENANT,f.request,null,"WARNING".equals(lease.kind()) ? "Approval.Quorum.SlaWarning" : "Approval.Quorum.SlaBreached",store.object(new String(json.bytes(data),StandardCharsets.UTF_8)));
            assertThat(f.jdbc.update("UPDATE apr_quorum_sla_timers SET status='COMPLETED',version=version+1,event_id=?,lease_owner=NULL,lease_until=NULL WHERE timer_id=? AND status='CLAIMED' AND lease_epoch=?",event,lease.timerId(),lease.epoch())).isEqualTo(1);
        });
    }
    @Test void publishedVersionPinsRemainOriginalWhenWorkflowHeadAdvancesButRetiredCurrentCategoryDenies() {
        var lease = ready(true); f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=current_version+1,version=version+1 WHERE workflow_id=?", f.workflow);
        var seal = f.tx.execute(status -> source.produce(lease));
        assertThat(seal.bindings().at("/source/workflow/workflowVersionId").asText()).isEqualTo(f.workflowVersion.toString());
        f.jdbc.update("UPDATE apr_form_categories SET lifecycle_state='INACTIVE',version=version+1 WHERE tenant_id=42");
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class);
    }
    @Test void managedPublishedRouteAndCapturedCategoryRemainOriginalAfterCatalogRetirementAndLegacyBindingRemoval() {
        var lease = ready(true); var route = route(); managed(route);
        f.jdbc.update("UPDATE apr_form_workspaces SET catalog_availability='RETIRED',workspace_version=workspace_version+1 WHERE tenant_id=42");
        f.jdbc.update("UPDATE apr_form_workflow_bindings SET lifecycle_state='INACTIVE',version=version+1 WHERE tenant_id=42 AND workflow_id=?", f.workflow);
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT',category_id=(SELECT category_id FROM apr_form_categories WHERE tenant_id=42 AND category_id<>apr_forms.category_id LIMIT 1),version=version+1 WHERE tenant_id=42");
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=current_version+1,version=version+1 WHERE workflow_id=?", f.workflow);
        String before = database(); var seal = f.tx.execute(status -> source.produce(lease));
        assertThat(seal.bindings().at("/source/workflow/workflowVersionId").asText()).isEqualTo(f.workflowVersion.toString());
        f.tx.executeWithoutResult(status -> source.unchanged(seal)); assertThat(database()).isEqualTo(before);
    }
    @Test void managedPublishedCrossWorkflowRouteIsNotTrustedMerelyBecauseItsMaterialHashIsValid() {
        var lease = ready(true); var route = route(); route.put("workflowVersionId", UUID.randomUUID().toString()); managed(route);
        String before = database(); assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOf(BaseException.class); assertThat(database()).isEqualTo(before);
    }
    @Test void nonLiveRetentionHeadHidesProducerAndDeliveryBeforeAnySourceProjection() {
        var lease = ready(true); var seal = f.tx.execute(status -> source.produce(lease));
        f.jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'PREPARED')",f.request);
        String before = database();
        assertThatThrownBy(() -> f.tx.execute(status -> source.produce(lease))).isInstanceOfSatisfying(BaseException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> f.tx.executeWithoutResult(status -> source.unchanged(seal))).isInstanceOf(BaseException.class);
        assertThat(database()).isEqualTo(before);
    }
    @Test void sourceRejectsReadOnlyRepeatableReadAndOtherDataSourceTransactions() {
        var lease = ready(true); var sourceDs = f.jdbc.getDataSource();
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(sourceDs);
        var readonly = new org.springframework.transaction.support.TransactionTemplate(manager); readonly.setReadOnly(true);
        var repeatable = new org.springframework.transaction.support.TransactionTemplate(manager); repeatable.setIsolationLevel(4);
        var otherDs = new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var other = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(otherDs));
        String before = database();
        for (var tx : List.of(readonly,repeatable,other)) assertThatThrownBy(() -> tx.execute(status -> source.produce(lease)))
                .isInstanceOfSatisfying(BaseException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThat(database()).isEqualTo(before);
    }
    Map<String, Object> route() {
        var row = f.jdbc.queryForMap("SELECT workflow.version,version.effective_from,version.effective_to FROM apr_workflow_versions version JOIN apr_workflow_definitions workflow ON workflow.workflow_id=version.workflow_id AND workflow.tenant_id=version.tenant_id WHERE version.workflow_version_id=?", f.workflowVersion);
        var route = new LinkedHashMap<String, Object>(); route.put("workflowId", f.workflow.toString()); route.put("workflowVersionId", f.workflowVersion.toString());
        route.put("workflowRevision", row.get("version")); route.put("workflowVersionNumber", f.pins.workflowVersion()); route.put("definitionSha256", f.pins.workflowDefinitionSha256());
        route.put("dataClassification", "INTERNAL"); route.put("slaMinutes", 60); route.put("effectiveFrom", row.get("effective_from")); route.put("effectiveTo", row.get("effective_to")); return route;
    }
    void managed(Map<String, Object> route) {
        var row = f.jdbc.queryForMap("SELECT version.form_id,version.form_version_id,form.category_id,version.schema_sha256 FROM apr_requests request JOIN apr_form_versions version ON version.tenant_id=request.tenant_id AND version.form_version_id=request.form_version_id JOIN apr_forms form ON form.tenant_id=version.tenant_id AND form.form_id=version.form_id WHERE request.request_id=?", f.request);
        var metadata = Map.<String, Object>of("categoryId", row.get("category_id").toString());
        var codec = new com.dwp.services.approval.forms.ApprovalFormMaterialCodec(mapper, (com.dwp.services.approval.forms.ApprovalFormLegacySchemaValidator) null);
        String hash = ((String) row.get("schema_sha256")).trim();
        f.jdbc.update("INSERT INTO apr_form_workspaces(tenant_id,form_id,published_form_version_id,created_by,updated_by) VALUES(42,?,?,91,91)", row.get("form_id"), row.get("form_version_id"));
        f.jdbc.update("INSERT INTO apr_form_version_material(tenant_id,form_id,form_version_id,schema_sha256,metadata_payload,route_payload,material_sha256,provenance,captured_by) VALUES(42,?,?,?,?::jsonb,?::jsonb,?,'PUBLISH_SNAPSHOT',91)",
                row.get("form_id"), row.get("form_version_id"), hash, codec.json(metadata), codec.json(route), codec.material(hash, metadata, route));
    }
    ApprovalWorkflowQuorumSlaRuntime.Lease ready(boolean reviewed) {
        if (reviewed) {
            f.jdbc.update("UPDATE apr_policy_rules SET version=version+1 WHERE tenant_id=42 AND policy_key IN('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION')");
            f.jdbc.update("INSERT INTO apr_policy_rule_versions(policy_version_id,tenant_id,policy_id,version_number,enforcement_mode,severity,lifecycle_state,rule_payload,change_reason,submitted_by,submitted_at,published_by,published_at,review_comment) "
                    + "SELECT gen_random_uuid(),tenant_id,policy_id,version+1,enforcement_mode,severity,lifecycle_state,rule_payload,'Explicit disposable reviewed source fixture',90,clock_timestamp()-interval '2 minutes',91,clock_timestamp()-interval '1 minute','Fixture review only' "
                    + "FROM apr_policy_rules WHERE tenant_id=42 AND policy_key IN('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION')");
        }
        var definition = one(ApprovalWorkflowQuorum.Mode.ALL, null); f.prepare(definition);
        UUID form = f.jdbc.queryForObject("SELECT immutable.form_version_id FROM apr_form_workflow_bindings binding "
                + "JOIN apr_forms form ON form.tenant_id=binding.tenant_id AND form.form_id=binding.form_id "
                + "JOIN apr_form_versions immutable ON immutable.tenant_id=form.tenant_id AND immutable.form_id=form.form_id AND immutable.version_number=form.current_version "
                + "WHERE binding.tenant_id=42 AND binding.workflow_id=? AND binding.binding_type='DEFAULT' AND binding.lifecycle_state='ACTIVE'", UUID.class, f.workflow);
        f.jdbc.update("UPDATE apr_requests SET form_version_id=? WHERE request_id=?", form, f.request);
        f.pins = f.runtime.canonicalPins(TENANT, f.request, definition); f.runtime.start(TENANT, f.request, f.pins, definition);
        f.dueTimers(); var lease=f.sla.claim("native-source-test", 60, 1).getFirst();
        if (reviewed) ApprovalSystemSlaOriginTestWiring.bind(this); return lease;
    }
    Map<String, Object> notificationRequest() {
        var row = f.jdbc.queryForMap("SELECT event_id,event_type,payload::text FROM apr_integration_outbox WHERE request_id=? AND event_type LIKE 'Approval.Quorum.Sla%'", f.request);
        String raw = (String) row.get("payload"); var envelope = json.parse(raw.getBytes(StandardCharsets.UTF_8)); var body = envelope.get("payload");
        var pins = new LinkedHashMap<String, JsonNode>();
        for (String key : List.of("requestTitle", "managementResourceSetKey", "stageKey", "authorityRevision", "stepId", "workflowVersionId", "formVersionId", "timerId", "workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256", "requestVersion", "stageVersion", "generation", "workflowVersion", "payloadRevision", "policyVersion", "leaseEpoch")) pins.put(key, body.get(key));
        return new LinkedHashMap<>(Map.of("eventId", row.get("event_id").toString(), "eventType", row.get("event_type"), "tenantId", TENANT,
                "requestId", f.request.toString(), "originalEnvelopeSha256", SystemSlaJson.sha(raw.getBytes(StandardCharsets.UTF_8)), "canonicalEnvelopeSha256", json.digest(envelope),
                "recipientSnapshotSha256", body.get("recipientSnapshotSha256").asText(), "sourcePinsSha256", json.digest(pins), "requestedRecipientUserIds", body.get("recipientUserIds")));
    }
    SystemSlaNotificationVerifier.Verified notification(Map<String, Object> request) {
        byte[] raw = json.bytes(request); Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", SystemSlaNotificationProtocol.TRANSPORT_ISSUER); claims.put("aud", SystemSlaNotificationProtocol.TRANSPORT_AUDIENCE); claims.put("sub", "dwp-notification-server");
        claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond()); claims.put("exp", now.plusSeconds(30).getEpochSecond()); claims.put("jti", UUID.randomUUID().toString());
        claims.put("purpose", SystemSlaNotificationProtocol.TRANSPORT_PURPOSE); claims.put("method", "POST"); claims.put("path", SystemSlaNotificationProtocol.PATH);
        claims.put("requestNonce", UUID.randomUUID().toString()); claims.put("requestBodySha256", SystemSlaJson.sha(raw)); claims.put("sourcePinsSha256", request.get("sourcePinsSha256"));
        try {
            var signed = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(TRANSPORT.getKeyID()).build(), new Payload(json.bytes(claims))); signed.sign(new RSASSASigner(TRANSPORT));
            var keys = new SystemSlaNotificationKeys(json, jwks(TRANSPORT), RESPONSE.toJSONString(), jwks(RESPONSE), List.of(OTHER.toPublicJWK()));
            return new SystemSlaNotificationVerifier(json, keys, Clock.systemUTC()).verify(raw, signed.serialize());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    String database() {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("apr_requests", "apr_request_payloads", "apr_quorum_stage_runtime", "apr_quorum_sla_timers", "apr_tasks", "apr_request_events", "apr_integration_outbox", "apr_policy_rule_versions", "apr_policy_version_source_records", "sys_audit_outbox","apr_system_sla_source_witnesses"))
            result.put(table, f.jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row) ORDER BY to_jsonb(row)::text),'[]'::jsonb)::text FROM " + table + " row", String.class));
        return json.digest(result);
    }
    static RSAKey key(String id) { try { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); } catch (Exception failure) { throw new AssertionError(failure); } }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
}
