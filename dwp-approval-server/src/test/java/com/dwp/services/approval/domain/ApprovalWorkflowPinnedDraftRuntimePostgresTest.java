package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowPinnedDraftRuntimePostgresTest {
    private static final Set<String> PERMISSIONS = Set.of(
            "APP.APPROVALS:VIEW",
            "ACTION.APPROVAL_REQUEST:CREATE",
            "ACTION.APPROVAL_REQUEST:UPDATE");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalCommandRepository commands;
    ApprovalRequestContext.Actor actor;
    UUID formId;
    UUID formVersion;

    @BeforeEach void initialize() throws Exception {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES);
        var definition = one(ApprovalWorkflowQuorum.Mode.ALL, null);
        f.prepareDraft(definition); f.bindTypedForm(definition);
        formVersion = f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, f.request);
        formId = f.jdbc.queryForObject("SELECT form_id FROM apr_form_versions WHERE form_version_id=?", UUID.class, formVersion);
        var named = new NamedParameterJdbcTemplate(f.jdbc);
        var mapper = new ObjectMapper().findAndRegisterModules();
        commands = new ApprovalCommandRepository(named, mapper);
        Map<String, Object> payload = mapper.readValue(
                f.jdbc.queryForObject(
                        "SELECT payload::text FROM apr_request_payloads WHERE request_id=?",
                        String.class, f.request),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        String canonical = ApprovalFormSchemaV2Canonical.json(
                ApprovalFormSchemaV2Canonical.freeze(payload));
        String payloadSha256 = ApprovalFormSchemaV2Canonical.sha256(canonical);
        f.jdbc.update(
                "UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",
                canonical, payloadSha256, f.request);
        f.jdbc.update("""
                INSERT INTO apr_request_payload_versions (
                    payload_version_id, tenant_id, request_id, revision_number,
                    payload, payload_sha256, change_type, changed_by, change_reason)
                SELECT ?, tenant_id, request_id, schema_version,
                       payload, payload_sha256, 'DRAFT_CREATED', ?, ?
                  FROM apr_request_payloads
                 WHERE request_id = ?
                """, UUID.randomUUID(), REQUESTER,
                "Disposable pinned-runtime baseline", f.request);
        var identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(TENANT, REQUESTER)).thenAnswer(ignored ->
                new ApprovalIdentityDirectory.Subject(
                        TENANT, REQUESTER, null, actor.personPublicId(), "Requester", null,
                        null, "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(PERMISSIONS)));
        ApprovalAttachmentLifecycleTestWiring.bindDefault(commands, named, identities, mapper);
        actor = new ApprovalRequestContext.Actor(
                REQUESTER, TENANT, person(REQUESTER), "Requester",
                Set.of("APPROVAL_OPERATOR"), PERMISSIONS);
    }

    @AfterEach void clearCurrentAuthority() {
        ApprovalRequestContext.clear();
    }

    private void update(UUID workflow, Map<String, Object> values) {
        ApprovalRequestContext.set(
                actor.userId(), actor.tenantId(), actor.personPublicId(), actor.displayName(),
                actor.roles(), actor.permissions());
        try {
            f.tx.executeWithoutResult(tx -> commands.updateDraft(actor, f.request,
                    new ApprovalDtos.UpdateDraftRequest(
                            workflow, formId, "Updated", "Runtime submission", "NORMAL", values, 0L),
                    "update"));
        } finally {
            ApprovalRequestContext.clear();
        }
    }
    private void unchanged() {
        assertEquals(0L, f.jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, f.request));
        assertEquals(1, f.count("apr_request_payload_versions"));
        assertEquals(0, f.count("apr_request_events"));
        assertEquals(0, f.count("apr_quorum_stage_runtime"));
    }

    @Test void actualTypedUpdateKeepsPublishedPinsWhileCurrentFormIsDraft() {
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=?", formId);
        update(f.workflow, Map.of("amount", "21.00"));
        assertEquals(formVersion, f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests", UUID.class));
        assertEquals(f.workflowVersion, f.jdbc.queryForObject("SELECT workflow_version_id FROM apr_requests", UUID.class));
        assertEquals("21", f.jdbc.queryForObject("SELECT payload->>'amount' FROM apr_request_payloads", String.class));
        assertEquals(2, f.count("apr_request_payload_versions"));
    }

    @Test void actualUpdateDoesNotAdoptAdvancedCurrentWorkflowVersionOrDefinition() {
        UUID advanced = UUID.randomUUID(); var definition = one(ApprovalWorkflowQuorum.Mode.COUNT, 1);
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,"
                + "definition_sha256,lifecycle_state,published_by) VALUES(?,42,?,91,?::jsonb,?,'PUBLISHED',100)",
                advanced, f.workflow, definition.canonicalJson(), definition.sha256());
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91 WHERE workflow_id=?", f.workflow);
        update(f.workflow, Map.of("amount", "22"));
        assertEquals(f.workflowVersion, f.jdbc.queryForObject("SELECT workflow_version_id FROM apr_requests", UUID.class));
        assertEquals(ApprovalWorkflowQuorum.Mode.ALL,
                commands.quorumWorkflow(TENANT, f.request).stages().getFirst().quorum().mode());
    }

    @Test void clientCannotSwitchWorkflowOnTypedUpdateAndNoLedgerIsWritten() {
        assertThrows(BaseException.class, () -> update(UUID.randomUUID(), Map.of("amount", "22")));
        unchanged();
    }

    @Test void unavailableTypedUserSourceAfterPinnedWorkflowValidationHasZeroWrites() {
        var schema = new ApprovalFormSchemaV2Compiler().compile(ApprovalFormSchemaV2CompilerTest.schema(
                ApprovalFormSchemaV2CompilerTest.field("summary", "TEXT"), ApprovalFormSchemaV2CompilerTest.field("reviewer", "USER")));
        UUID userVersion = UUID.randomUUID();
        f.jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,"
                + "lifecycle_state,published_by) VALUES(?,42,?,100,?::jsonb,?,'PUBLISHED',100)",
                userVersion, formId, schema.canonicalJson(), schema.sha256());
        f.jdbc.update("UPDATE apr_requests SET form_version_id=? WHERE request_id=?", userVersion, f.request);
        assertThrows(BaseException.class, () -> update(f.workflow, Map.of("reviewer", person(101).toString())));
        unchanged();
    }

    @Test void expiredBoundWorkflowOrDisabledPolicyCannotBeHealedByCurrentVersion() {
        f.jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='DISABLED' WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");
        assertThrows(BaseException.class, () -> update(f.workflow, Map.of("amount", "22"))); unchanged();
        f.jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='ACTIVE' WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> f.jdbc.update(
                "UPDATE apr_workflow_versions SET effective_to=now()-interval '1 second' WHERE workflow_version_id=?", f.workflowVersion));
        UUID expiredVersion = UUID.randomUUID(); var definition = one(ApprovalWorkflowQuorum.Mode.ALL, null);
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,"
                + "definition_sha256,lifecycle_state,published_by,effective_to) VALUES(?,42,?,91,?::jsonb,?,'PUBLISHED',100,now()-interval '1 second')",
                expiredVersion, f.workflow, definition.canonicalJson(), definition.sha256());
        UUID expiredRequest = UUID.randomUUID();
        f.jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,
                    requester_user_id,requester_person_public_id,status,data_classification,management_resource_set_key)
                SELECT ?,tenant_id,?, ?,form_version_id,title,requester_user_id,requester_person_public_id,
                    'DRAFT',data_classification,management_resource_set_key FROM apr_requests WHERE request_id=?
                """, expiredRequest, "EXPIRED-" + expiredRequest, expiredVersion, f.request);
        f.jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) "
                + "VALUES(42,?,'{}'::jsonb,?,1)", expiredRequest, "a".repeat(64));
        f.request = expiredRequest;
        assertThrows(BaseException.class, () -> update(f.workflow, Map.of("amount", "22"))); unchanged();
    }

    @Test void exactLegacyNullPersonIdentityIsNotReplacedWithInventedOpaqueIdentity() {
        f.jdbc.update("UPDATE apr_requests SET requester_person_public_id=NULL WHERE request_id=?", f.request);
        actor = new ApprovalRequestContext.Actor(
                REQUESTER, TENANT, null, "Legacy", Set.of("APPROVAL_OPERATOR"), PERMISSIONS);
        update(f.workflow, Map.of("amount", "22"));
        assertNull(f.jdbc.queryForObject("SELECT requester_person_public_id FROM apr_requests", UUID.class));
    }

    @Test void fabricatedRecoveryRouteAliasDeniesWithZeroWritesAndExactCanonicalRecoverySucceeds() {
        try (var revisions = org.mockito.Mockito.mockStatic(ApprovalDecisionRevisionContext.class);
                var scopes = org.mockito.Mockito.mockStatic(ApprovalManagementScopeContext.class);
                var pilot = org.mockito.Mockito.mockStatic(ApprovalPilotAuthorizationContext.class)) {
            scopes.when(ApprovalManagementScopeContext::current).thenReturn(java.util.Optional.of(
                    new ApprovalManagementScopeContext.Evidence("scope", "RS_APPROVALS")));
            pilot.when(ApprovalPilotAuthorizationContext::current).thenReturn(java.util.Optional.of(List.of(
                    new ApprovalPilotPepRegistry.RouteAuthority(
                            "route.approvals.work.request-draft-recover.action",
                            "ACTION", "full-work", false,
                            Set.of("predicate.approval.own-request.v1"),
                            "approvals.work.request.update", null, null, false, null, null))));
            revisions.when(ApprovalDecisionRevisionContext::current).thenReturn(java.util.Optional.of(
                    new ApprovalDecisionRevisionContext.Evidence("psr-" + "c".repeat(64), java.time.OffsetDateTime.now().plusSeconds(30),
                            "context", "scope", "route.approvals.work.drafts.recover.action", "110")));
            var denied = assertThrows(BaseException.class, () -> update(f.workflow, Map.of("amount", "22")));
            assertEquals(com.dwp.core.common.ErrorCode.FORBIDDEN, denied.getErrorCode());
            assertEquals(403, denied.getErrorCode().getHttpStatus().value());
            unchanged();
            revisions.when(ApprovalDecisionRevisionContext::current).thenReturn(java.util.Optional.of(
                    new ApprovalDecisionRevisionContext.Evidence("psr-" + "c".repeat(64), java.time.OffsetDateTime.now().plusSeconds(30),
                            "context", "scope", "route.approvals.work.request-draft-recover.action", "110")));
            update(f.workflow, Map.of("amount", "22"));
            assertEquals(1L, f.jdbc.queryForObject("SELECT version FROM apr_requests", Long.class));
        }
    }
}
