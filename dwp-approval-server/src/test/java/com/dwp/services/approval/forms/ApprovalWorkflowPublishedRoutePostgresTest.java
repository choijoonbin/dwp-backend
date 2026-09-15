package com.dwp.services.approval.forms;

import static com.dwp.services.approval.domain.ApprovalWorkflowPublishedRouteRuntime.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorum;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real migration/DB pins; the internal seal and independent authority are explicit test fixtures, not Auth proof. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowPublishedRoutePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ApprovalFormMaterialCodec CODEC = new ApprovalFormMaterialCodec(new com.fasterxml.jackson.databind.ObjectMapper(), (ApprovalFormLegacySchemaValidator) null);
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;
    TransactionTemplate tx;
    Actor actor = new Actor(99L, 42L, UUID.nameUUIDFromBytes(new byte[] {99}), "", Set.of(), Set.of());
    UUID workflow, version, form, formVersion, category;
    String resourceSet;
    ApprovalFormPublishedRoutePin pin;
    Instant expiry = Instant.now().plusSeconds(300);
    AtomicInteger assemblies, legacyCalls;
    BiConsumer<String, Integer> legacy;

    @BeforeEach void initialize() { initialize(false); }
    void initialize(boolean legacyWorkflow) { initialize(legacyWorkflow, false, "PUBLISH_SNAPSHOT"); }
    void initialize(boolean legacyWorkflow, boolean unboundWorkflow, String provenance) {
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean(); flyway.migrate();
        jdbc = new JdbcTemplate(source); named = new NamedParameterJdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        workflow = jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
        form = jdbc.queryForObject("SELECT form_id FROM apr_form_workflow_bindings WHERE tenant_id=42 AND workflow_id=? AND lifecycle_state='ACTIVE' LIMIT 1", UUID.class, workflow);
        category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, form);
        resourceSet = jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE workflow_id=?", String.class, workflow);
        if (unboundWorkflow) {
            UUID newWorkflow = UUID.randomUUID();
            jdbc.update("INSERT INTO apr_workflow_definitions(workflow_id,tenant_id,workflow_key,name_ko,name_en,description_ko,description_en,category,"
                    + "data_classification,lifecycle_state,sla_minutes,management_resource_set_key) "
                    + "SELECT ?,tenant_id,'MODERN_UNBOUND',name_ko,name_en,description_ko,description_en,category,'INTERNAL','PUBLISHED',60,management_resource_set_key "
                    + "FROM apr_workflow_definitions WHERE workflow_id=?", newWorkflow, workflow);
            workflow = newWorkflow;
        }
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(new ApprovalWorkflowQuorumDefinition.Stage(
                "FINANCE", "Finance", "FINANCE_REVIEWER", new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ANY, null), 15, List.of())));
        String raw = definition.canonicalJson(), hash = definition.sha256();
        if (legacyWorkflow) {
            raw = jdbc.queryForObject("SELECT definition::text FROM apr_workflow_versions WHERE workflow_id=? ORDER BY version_number LIMIT 1", String.class, workflow);
            hash = jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_id=? ORDER BY version_number LIMIT 1", String.class, workflow).strip();
        }
        version = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) "
                + "VALUES(?,42,?,90,?::jsonb,?,'PUBLISHED')", version, workflow, raw, hash);
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=90,sla_minutes=60 WHERE workflow_id=?", workflow);
        var schema = new ApprovalFormSchemaV2Compiler().compile(Map.of("schemaContract", ApprovalFormSchemaV2.CONTRACT, "schemaVersion", 2,
                "fields", List.of(Map.of("key", "summary", "type", "TEXT", "labelKo", "Summary", "labelEn", "Summary", "required", true))));
        formVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) "
                + "VALUES(?,42,?,90,?::jsonb,?,'PUBLISHED')", formVersion, form, schema.canonicalJson(), schema.sha256());
        jdbc.update("UPDATE apr_forms SET current_version=90,lifecycle_state='PUBLISHED' WHERE form_id=?", form);
        jdbc.update("INSERT INTO apr_form_workspaces(tenant_id,form_id,published_form_version_id,created_by,updated_by) VALUES(42,?,?,99,99)", form, formVersion);
        Map<String, Object> metadata = Map.of("categoryId", category.toString());
        long workflowRevision = jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE workflow_id=?", Long.class, workflow);
        var route = new ApprovalFormPublishedRoutePin.WorkflowRoute(workflow, version, workflowRevision, 90, hash, "INTERNAL", 60, null, null);
        var routeMap = routeMap(route);
        String digest = sha(canonical(Map.of("schemaSha256", schema.sha256(), "metadata", metadata, "route", routeMap)));
        Instant captured = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO apr_form_version_material(tenant_id,form_id,form_version_id,schema_sha256,metadata_payload,route_payload,material_sha256,provenance,captured_at,captured_by) "
                + "VALUES(42,?,?,?,?::jsonb,?::jsonb,?,?,?,99)", form, formVersion, schema.sha256(), canonical(metadata), canonical(routeMap), digest, provenance, java.sql.Timestamp.from(captured));
        long formRevision = jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?", Long.class, form);
        long categoryRevision = jdbc.queryForObject("SELECT version FROM apr_form_categories WHERE category_id=?", Long.class, category);
        pin = ApprovalFormPublishedRoutePin.verified(new ApprovalFormPublishedRoutePin.Observation(42, form, formVersion, schema.sha256(),
                schema.canonicalJson(), digest, formRevision, 0, resourceSet, category, categoryRevision, null, null,
                ApprovalFormPublishedRoutePin.Operation.NEW_INITIATION, route, metadata, provenance, captured));
        assemblies = new AtomicInteger(); legacyCalls = new AtomicInteger();
        legacy = (json, sla) -> { assertEquals(60, sla); assertFalse(json.contains("schemaContract")); legacyCalls.incrementAndGet(); };
    }

    AuthorityObservation observation(String revision) {
        return new AuthorityObservation(42, 99, actor.personPublicId(), workflow, resourceSet,
                "route.approvals.work.request-create.action", "psr-" + "a".repeat(64), revision, expiry);
    }
    VerifiedPublishedWorkflow resolve(ApprovalFormPublishedRoutePin value, CurrentWorkflowAuthorityGuard guard) {
        return tx.execute(status -> requirePublishedRoute(named, actor, value, verified -> { assemblies.incrementAndGet(); return verified; }, legacy, guard));
    }
    VerifiedPublishedWorkflow resolve() { return resolve(pin, (a, p) -> observation("current-source-1")); }
    void assertFailure(ErrorCode expected, Runnable action) {
        long requests = jdbc.queryForObject("SELECT count(*) FROM apr_requests", Long.class);
        long events = jdbc.queryForObject("SELECT count(*) FROM apr_form_lifecycle_events", Long.class);
        assertEquals(expected, assertThrows(BaseException.class, action::run).getErrorCode());
        assertEquals(0, assemblies.get());
        assertEquals(requests, jdbc.queryForObject("SELECT count(*) FROM apr_requests", Long.class));
        assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM apr_form_lifecycle_events", Long.class));
    }

    @Test void currentHeadAdvanceCannotRetargetOldVersionClassificationOrSla() {
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,version=version+1,sla_minutes=120,data_classification='RESTRICTED' WHERE workflow_id=?", workflow);
        var result = resolve();
        assertEquals(version, result.workflowVersionId()); assertEquals(90, result.workflowVersion());
        assertEquals(60, result.slaMinutes()); assertEquals("INTERNAL", result.dataClassification());
        assertEquals(formVersion, result.formVersionId()); assertEquals(64, result.policySha256().length());
        assertTrue(result.policyVersion() >= 1); assertEquals(1, assemblies.get()); assertEquals(0, legacyCalls.get());
    }
    @Test void noIndependentAuthorityIsUnavailableBeforeAnyAdditionalSql() {
        var noSql = mock(NamedParameterJdbcTemplate.class);
        assertFailure(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> tx.execute(status -> requirePublishedRoute(noSql, actor, pin,
                Function.identity(), legacy, null)));
        verifyNoInteractions(noSql);
    }
    @Test void unknownAuthorityNeverAssembles() {
        assertFailure(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> resolve(pin, (a, p) -> null));
    }
    @Test void authorityRevisionDriftAfterLockedDbVerificationNeverAssembles() {
        var calls = new AtomicInteger();
        assertFailure(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> resolve(pin, (a, p) -> observation("source-" + calls.incrementAndGet())));
        assertEquals(2, calls.get());
    }
    @Test void substitutedWorkflowVersionCannotAssemble() {
        var original = pin.workflow();
        var wrong = new ApprovalFormPublishedRoutePin.WorkflowRoute(workflow, UUID.randomUUID(), original.workflowRevision(), 90,
                original.definitionSha256(), "INTERNAL", 60, null, null);
        assertFailure(ErrorCode.RESOURCE_CONFLICT, () -> resolve(with(wrong, pin.metadata(), pin.operation()), (a, p) -> observation("source")));
    }
    @Test void crossResourceWorkflowCannotAssemble() {
        var otherScope = ApprovalFormPublishedRoutePin.verified(new ApprovalFormPublishedRoutePin.Observation(pin.tenantId(), pin.formId(), pin.formVersionId(),
                pin.schemaSha256(), pin.schemaJson(), pin.materialDigest(), pin.formRevision(), pin.workspaceRevision(), "OTHER_SCOPE", pin.categoryId(),
                pin.categoryRevision(), null, null, pin.operation(), pin.workflow(), pin.metadata(), pin.metadataProvenance(), pin.capturedAt()));
        assertFailure(ErrorCode.RESOURCE_CONFLICT, () -> resolve(otherScope, (a, p) -> new AuthorityObservation(42,99,actor.personPublicId(),
                workflow,"OTHER_SCOPE","route.approvals.work.request-create.action","psr-a","source",expiry)));
    }
    @Test void retiredCatalogAndDraftFormCannotBeUsedForNewInitiation() {
        jdbc.update("UPDATE apr_form_workspaces SET catalog_availability='RETIRED' WHERE form_id=?", form);
        assertFailure(ErrorCode.RESOURCE_CONFLICT, this::resolve);
        jdbc.update("UPDATE apr_form_workspaces SET catalog_availability='ACTIVE' WHERE form_id=?", form);
        jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=?", form);
        assertFailure(ErrorCode.RESOURCE_CONFLICT, this::resolve);
    }
    @Test void substitutedMaterialCannotAssemble() {
        assertFailure(ErrorCode.RESOURCE_CONFLICT, () -> resolve(with(pin.workflow(), Map.of("categoryId", category.toString(), "name", "Invented"), pin.operation()),
                (a, p) -> observation("source")));
    }
    @Test void existingRequestCannotBorrowNewInitiationPort() {
        assertFailure(ErrorCode.RESOURCE_CONFLICT, () -> resolve(with(pin.workflow(), pin.metadata(), ApprovalFormPublishedRoutePin.Operation.EXISTING_REQUEST),
                (a, p) -> observation("source")));
    }
    @Test void inactiveCurrentPolicyCannotAssemble() {
        jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='RETIRED' WHERE tenant_id=42 AND management_resource_set_key=? AND policy_key='SLA_ESCALATION'", resourceSet);
        assertFailure(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, this::resolve);
    }
    @Test void fabricatedDraftCreateAliasIsNotAnAuthorityRoute() {
        assertFailure(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> resolve(pin, (a, p) -> new AuthorityObservation(42,99,actor.personPublicId(),
                workflow,resourceSet,"route.approvals.work.request-draft-create.action","psr-a","source",expiry)));
    }
    @Test void originalLegacyValidatorReceivesCapturedSlaNotCurrentHeadSla() {
        initialize(true); jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,sla_minutes=120 WHERE workflow_id=?", workflow);
        assertEquals(60, resolve().slaMinutes()); assertEquals(1, legacyCalls.get());
    }
    @Test void managedPublishedRouteCanUseWorkflowWithoutAnyLegacyBinding() {
        initialize(false, true, "PUBLISH_SNAPSHOT");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM apr_form_workflow_bindings WHERE workflow_id=?", Integer.class, workflow));
        assertEquals(version, resolve().workflowVersionId());
    }
    @Test void legacyCaptureStillRequiresItsOriginalActiveEffectiveBinding() {
        initialize(false, false, "LEGACY_CAPTURE_TIME");
        jdbc.update("UPDATE apr_form_workflow_bindings SET lifecycle_state='INACTIVE' WHERE tenant_id=42 AND form_id=? AND workflow_id=?", form, workflow);
        assertFailure(ErrorCode.RESOURCE_CONFLICT, this::resolve);
    }
    @Test void activeLegacyCaptureBindingRemainsSupportedWithoutHeadCas() {
        initialize(false, false, "LEGACY_CAPTURE_TIME");
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,version=version+1 WHERE workflow_id=?", workflow);
        assertEquals(version, resolve().workflowVersionId());
    }

    ApprovalFormPublishedRoutePin with(ApprovalFormPublishedRoutePin.WorkflowRoute route, Map<String,Object> metadata, ApprovalFormPublishedRoutePin.Operation operation) {
        return ApprovalFormPublishedRoutePin.verified(new ApprovalFormPublishedRoutePin.Observation(pin.tenantId(), pin.formId(), pin.formVersionId(), pin.schemaSha256(),
                pin.schemaJson(), pin.materialDigest(), pin.formRevision(), pin.workspaceRevision(), pin.resourceSetKey(), pin.categoryId(), pin.categoryRevision(),
                null, null, operation, route, metadata, pin.metadataProvenance(), pin.capturedAt()));
    }
    Map<String,Object> routeMap(ApprovalFormPublishedRoutePin.WorkflowRoute value) {
        var result = new java.util.LinkedHashMap<String,Object>(); result.put("workflowId", value.workflowId().toString());
        result.put("workflowVersionId", value.workflowVersionId().toString()); result.put("workflowRevision", value.workflowRevision());
        result.put("workflowVersionNumber", value.workflowVersionNumber()); result.put("definitionSha256", value.definitionSha256());
        result.put("dataClassification", value.dataClassification()); result.put("slaMinutes", value.slaMinutes());
        result.put("effectiveFrom", null); result.put("effectiveTo", null); return result;
    }
    static String canonical(Map<String, ?> value) { return CODEC.json(value); }
    static String sha(String value) { return CODEC.sha(value); }
}
