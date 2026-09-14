package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.dwp.core.exception.BaseException;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.api.ApprovalAdminController;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumManagementPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    MockMvc mvc;

    @BeforeEach void initialize() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES);
        var jdbc = new NamedParameterJdbcTemplate(f.jdbc);
        var commands = new ApprovalCommandRepository(jdbc, mapper);
        var service = new ApprovalService(new ApprovalQueryRepository(jdbc, mapper), commands,
                new AuditOutboxRecorder(jdbc, mapper, "dwp-approval-server", "test", "test"), mock(ApprovalIdentityDirectory.class));
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalAdminController(service, mock(ApprovalResponseProjection.class)))
                .setControllerAdvice(new Failures()).build();
        ApprovalRequestContext.set(99L, 42L, person(99), Set.of("APPROVAL_DESIGNER"), Set.of("ADMIN.APPROVAL_DESIGN:CREATE"));
    }
    @AfterEach void clear() { ApprovalRequestContext.clear(); }

    @RestControllerAdvice static class Failures {
        @ExceptionHandler(BaseException.class) ResponseEntity<Void> reject(BaseException exception) {
            return ResponseEntity.status(409).build();
        }
    }

    private Map<String, Object> base() {
        return new LinkedHashMap<>(Map.of("workflowKey", "QUORUM_TEST", "nameKo", "Quorum", "nameEn", "Quorum",
                "descriptionKo", "Approval", "descriptionEn", "Approval", "category", "GENERAL", "dataClassification", "INTERNAL",
                "slaMinutes", 60, "ownerGroupRef", "FINANCE"));
    }
    private Map<String, Object> typed(Mode mode, Integer value) throws Exception {
        return mapper.readValue(ApprovalWorkflowQuorumWiringPostgresTest.one(mode, value).canonicalJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }
    private int create(Map<String, Object> body) {
        return f.tx.execute(status -> {
            try { return mvc.perform(post("/v1/admin/workflows").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus(); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    @Test void actualControllerCreatesAndUpdatesCanonicalTypedWorkflowWithCas() throws Exception {
        var body = base(); body.put("typedDefinition", typed(Mode.COUNT, 2));
        assertEquals(200, create(body));
        UUID id = f.jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE workflow_key='QUORUM_TEST' AND tenant_id=42", UUID.class);
        assertEquals(ApprovalWorkflowQuorumWiringPostgresTest.one(Mode.COUNT, 2).sha256(),
                f.jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_id=?", String.class, id).strip());
        body.remove("workflowKey"); body.put("expectedVersion", 0); body.put("typedDefinition", typed(Mode.PERCENT, 67));
        f.tx.executeWithoutResult(status -> {
            try { assertEquals(200, mvc.perform(put("/v1/admin/workflows/" + id + "/draft").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus()); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        assertEquals(ApprovalWorkflowQuorumWiringPostgresTest.one(Mode.PERCENT, 67).sha256(),
                f.jdbc.queryForObject("SELECT definition_sha256 FROM apr_workflow_versions WHERE workflow_id=?", String.class, id).strip());
        f.tx.executeWithoutResult(status -> {
            try { assertEquals(409, mvc.perform(put("/v1/admin/workflows/" + id + "/draft").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus()); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    @Test void actualBeanValidationRejectsMissingBothEmptyAndTypedSlaMismatch() throws Exception {
        assertEquals(400, create(base()));
        var body = base(); body.put("steps", List.of()); assertEquals(400, create(body));
        body.put("typedDefinition", typed(Mode.ANY, null)); assertEquals(400, create(body));
        body.remove("steps"); body.put("slaMinutes", 90); assertEquals(409, create(body));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM apr_workflow_definitions WHERE workflow_key='QUORUM_TEST'", Integer.class));
    }

    @Test void legacyAnyInputStillCreatesUntaggedExactOldDefinition() {
        var body = base(); body.put("steps", List.of(Map.of("key", "PRIMARY", "name", "Primary", "mode", "ANY",
                "candidateRole", "FINANCE_REVIEWER", "slaMinutes", 15)));
        assertEquals(200, create(body));
        var definition = f.jdbc.queryForObject("SELECT definition::text FROM apr_workflow_versions WHERE workflow_id="
                + "(SELECT workflow_id FROM apr_workflow_definitions WHERE workflow_key='QUORUM_TEST')", String.class);
        assertFalse(definition.contains("schemaContract")); assertTrue(definition.contains("ANY"));
    }
}
