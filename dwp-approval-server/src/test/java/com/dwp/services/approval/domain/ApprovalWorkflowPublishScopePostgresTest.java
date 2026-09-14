package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowPublishScopePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalCommandRepository commands;
    UUID workflow;

    @BeforeEach void initialize() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES);
        commands = new ApprovalCommandRepository(new NamedParameterJdbcTemplate(f.jdbc), new ObjectMapper().findAndRegisterModules());
        workflow = UUID.randomUUID();
        var definition = ApprovalWorkflowQuorumPostgresFixture.one(ApprovalWorkflowQuorum.Mode.ALL, null);
        f.jdbc.update("""
                INSERT INTO apr_workflow_definitions(workflow_id,tenant_id,workflow_key,name_ko,name_en,
                    description_ko,description_en,category,management_resource_set_key,sla_minutes,created_by,updated_by)
                VALUES(?,42,?,'Quorum','Quorum','Description','Description','GENERAL','RS_TEAM_A',60,99,99)
                """, workflow, "PUBLISH_" + workflow);
        f.jdbc.update("""
                INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,
                    definition,definition_sha256,lifecycle_state,created_by)
                VALUES(?,42,?,1,?::jsonb,?,'DRAFT',99)
                """, UUID.randomUUID(), workflow, definition.canonicalJson(), definition.sha256());
        scope("scope-team-a", "RS_TEAM_A");
    }
    @AfterEach void clear() { ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear"); }
    private void scope(String key, String resourceSet) {
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "set", key, resourceSet);
    }

    private ApprovalRequestContext.Actor actor(long user) {
        return new ApprovalRequestContext.Actor(user, 42L, null, "Manager", Set.of("APPROVAL_ADMIN"),
                Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"));
    }

    private String snapshot() {
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'definition',(SELECT to_jsonb(definition) FROM apr_workflow_definitions definition WHERE workflow_id=?),
                    'versions',(SELECT jsonb_agg(to_jsonb(version) ORDER BY version_number) FROM apr_workflow_versions version WHERE workflow_id=?),
                    'requestEvents',(SELECT count(*) FROM apr_request_events),
                    'integrationEvents',(SELECT count(*) FROM apr_integration_outbox))::text
                """, String.class, workflow, workflow);
    }

    private void conflict(UUID id, long user, long expectedVersion) {
        String before = snapshot();
        var error = assertThrows(BaseException.class, () -> f.tx.executeWithoutResult(status ->
                commands.publishWorkflow(actor(user), id, expectedVersion, "publish-correlation")));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, error.getErrorCode());
        assertEquals(409, error.getErrorCode().getHttpStatus().value());
        assertEquals(before, snapshot());
    }

    @Test void sameScopeNonMakerPublishesTypedDefinitionWithExactVersionAndHash() {
        String hash = f.jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_id=?", String.class, workflow);
        f.tx.executeWithoutResult(status -> commands.publishWorkflow(actor(17), workflow, 0, "publish-correlation"));
        assertEquals("PUBLISHED", f.jdbc.queryForObject("SELECT lifecycle_state FROM apr_workflow_definitions WHERE workflow_id=?", String.class, workflow));
        assertEquals(1L, f.jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE workflow_id=?", Long.class, workflow));
        assertEquals("PUBLISHED", f.jdbc.queryForObject("SELECT lifecycle_state FROM apr_workflow_versions WHERE workflow_id=?", String.class, workflow));
        assertEquals(hash, f.jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_id=?", String.class, workflow));
    }

    @Test void crossScopeNoRowRetainsOriginal409WithoutCrossScopeWrites() {
        scope("scope-team-b", "RS_TEAM_B"); conflict(workflow, 17, 0);
    }

    @Test void missingWorkflowRetainsOriginal409WithoutWrites() { conflict(UUID.randomUUID(), 17, 0); }

    @Test void missingCurrentVersionJoinRetainsOriginal409WithoutPublishingHead() {
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=2 WHERE workflow_id=?", workflow);
        conflict(workflow, 17, 0);
    }

    @Test void currentDraftMakerCannotPublishAndHasZeroWrites() { conflict(workflow, 99, 0); }

    @Test void staleExpectedVersionCannotPublishAndHasZeroWrites() { conflict(workflow, 17, 1); }
}
