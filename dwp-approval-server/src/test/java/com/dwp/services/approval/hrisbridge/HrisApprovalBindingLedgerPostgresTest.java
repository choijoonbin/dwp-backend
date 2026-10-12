package com.dwp.services.approval.hrisbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisApprovalBindingLedgerPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final long TENANT = 42059L;

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private HrisApprovalBridgeService service;
    private UUID workflowVersion;
    private long workflowVersionNumber;
    private UUID formVersion;

    @BeforeEach
    void initialize() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, TENANT);
        workflowVersion = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,workflow_version_id LIMIT 1
                """, UUID.class, TENANT);
        workflowVersionNumber = jdbc.queryForObject(
                "SELECT version_number FROM apr_workflow_versions WHERE workflow_version_id=?",
                Long.class, workflowVersion);
        formVersion = jdbc.queryForObject("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,form_version_id LIMIT 1
                """, UUID.class, TENANT);
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new HrisApprovalBridgeService(mapper, null, null, null,
                null, null, new NamedParameterJdbcTemplate(source),
                new DataSourceTransactionManager(source));
    }

    @Test
    void allThreeAliasesReplayExactBytesAndRejectDigestChangeOrSplitOwnership()
            throws Exception {
        UUID requestA = uuid(91);
        UUID requestB = uuid(92);
        UUID employeeA = uuid(93);
        UUID employeeB = uuid(94);
        String commandA = "a".repeat(64);
        String commandB = "b".repeat(64);
        byte[] responseA = mapper.writeValueAsBytes(
                new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                        requestA, 1, "PENDING", commandA));
        byte[] responseB = mapper.writeValueAsBytes(
                new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                        requestB, 1, "PENDING", commandB));
        insert(requestA, employeeA, 3, "idempotency-key-00000091", commandA, responseA);

        var aliasesA = new HrisApprovalBridgeService.Aliases(
                TENANT, "employee-change:" + employeeA, 3,
                requestA, "idempotency-key-00000091");
        var replay = service.findExisting(aliasesA, commandA);
        assertThat(replay).isPresent();
        assertThat(replay.orElseThrow().exactResponseBytes()).containsExactly(responseA);
        assertThatThrownBy(() -> service.findExisting(aliasesA, "c".repeat(64)))
                .isInstanceOf(HrisApprovalProtocolException.class)
                .hasMessage("IDEMPOTENCY_IDENTITY_COMMAND_MISMATCH");

        insert(requestB, employeeB, 4, "idempotency-key-00000092", commandB, responseB);
        var split = new HrisApprovalBridgeService.Aliases(
                TENANT, "employee-change:" + employeeA, 3,
                requestB, "idempotency-key-00000091");
        assertThatThrownBy(() -> service.findExisting(split, commandA))
                .isInstanceOf(HrisApprovalProtocolException.class)
                .hasMessage("IDEMPOTENCY_ALIAS_SPLIT_BRAIN");
    }

    private void insert(UUID request, UUID employee, long revision,
            String idempotency, String commandDigest, byte[] response) {
        jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,
                    workflow_version_id,form_version_id,title,requester_user_id,
                    requester_person_public_id,status)
                VALUES(?,?,?, ?,?,'HRIS',99,?,'IN_REVIEW')
                """, request, TENANT, "HRIS-" + request,
                workflowVersion, formVersion, uuid(99));
        byte[] body = ("{\"request\":\"" + request + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO apr_hris_employee_change_approval_bindings(
                    binding_id,tenant_id,approval_request_id,employee_change_request_public_id,
                    subject_ref,subject_revision,idempotency_key,
                    requester_person_public_id,requester_user_id,
                    workflow_key,workflow_version,workflow_version_id,form_version_id,
                    frozen_policy_version,original_correlation_id,original_payload_digest,
                    exact_request_body,request_body_sha256,command_digest,
                    exact_response_body,response_body_sha256)
                VALUES(?,?,?,?,?,?,?,?,99,'hris.employee-change.v1',?,?,?,?,?,?, ?,?,?,?,?)
                """, UUID.randomUUID(), TENANT, request, employee,
                "employee-change:" + employee, revision, idempotency, uuid(99),
                workflowVersionNumber, workflowVersion, formVersion, 7L,
                uuid((int) revision), "d".repeat(64), body,
                HrisApprovalBridgeV2.sha256(body), commandDigest,
                response, HrisApprovalBridgeV2.sha256(response));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
