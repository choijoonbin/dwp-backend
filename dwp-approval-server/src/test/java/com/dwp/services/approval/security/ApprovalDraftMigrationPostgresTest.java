package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDraftMigrationDtos;
import com.dwp.services.approval.domain.ApprovalDraftMigrationMapper;
import com.dwp.services.approval.domain.ApprovalDraftMigrationRepository;
import com.dwp.services.approval.domain.ApprovalDraftMigrationService;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDraftMigrationPostgresTest extends ApprovalDraftPostgresFixture {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private ApprovalDraftMigrationService migration;

    @BeforeEach
    void setUp() {
        initialize(POSTGRES);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        var migrationRepository = new ApprovalDraftMigrationRepository(named, objectMapper);
        var audit = new AuditOutboxRecorder(named, objectMapper, "dwp-approval-server", "test", "test");
        migration = new ApprovalDraftMigrationService(migrationRepository,
                new ApprovalDraftMigrationMapper(objectMapper, commands), repository,
                new ApprovalWorkAuthority(identities), approvals, audit);
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void previewsAndCreatesASeparateCurrentDraftWhilePreservingRetiredSource() {
        var source = tx(() -> drafts.create(body("Legacy draft"), "migration-source", "source"));
        String originalPayload = payload(source.requestId());
        UUID originalFormVersion = formVersion(source.requestId());
        Target target = publishTargetSchema();
        long audits = count("sys_audit_outbox");

        ApprovalDraftMigrationDtos.Preview preview = tx(() -> migration.preview(
                source.requestId(), formId, workflowId));

        assertThat(preview.migrationRequired()).isTrue();
        assertThat(preview.routeCompatible()).isTrue();
        assertThat(preview.source().formVersionId()).isEqualTo(originalFormVersion);
        assertThat(preview.target().formVersionId()).isEqualTo(target.formVersionId());
        assertThat(preview.mappedFields()).contains("summary", "systemName", "accessRole");
        assertThat(preview.droppedFields()).contains("compensatingControl");
        assertThat(preview.incompatibleFields()).isEmpty();
        assertThat(preview.requiredFieldsToComplete()).containsExactly("businessJustification");

        ApprovalDraftMigrationDtos.Result result = tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()),
                "migration-command", "corr-migration"));

        UUID draftId = result.draft().requestId();
        assertThat(draftId).isNotEqualTo(source.requestId());
        assertThat(result.draft().status()).isEqualTo("DRAFT");
        assertThat(formVersion(draftId)).isEqualTo(target.formVersionId());
        assertThat(payload(source.requestId())).isEqualTo(originalPayload);
        assertThat(formVersion(source.requestId())).isEqualTo(originalFormVersion);
        assertThat(payload(draftId)).contains("\"createdFrom\": \"" + source.requestId() + "\"");
        assertThat(payload(draftId)).doesNotContain("compensatingControl");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_request_events WHERE tenant_id=42 "
                + "AND request_id=? AND event_type='REQUEST_DRAFT_MIGRATED'", Long.class,
                source.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_request_events WHERE tenant_id=42 "
                + "AND request_id=? AND event_type='REQUEST_DRAFT_CREATED_FROM_MIGRATION'", Long.class,
                draftId)).isEqualTo(1);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits + 2);

        ApprovalDraftMigrationDtos.Result replay = tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()),
                "migration-command", "corr-migration"));
        assertThat(replay).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_requests WHERE tenant_id=42 "
                + "AND source_reference=?", Long.class, source.requestId().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_draft_commands WHERE tenant_id=42 "
                + "AND command_route=? AND idempotency_key='migration-command'", Long.class,
                route(source.requestId()))).isEqualTo(1);
    }

    @Test
    void staleTargetAndCurrentSourceCannotProduceAMigrationDraft() {
        var source = tx(() -> drafts.create(body("Target fence"), "target-fence-source", null));
        Target first = publishTargetSchema();
        var preview = tx(() -> migration.preview(source.requestId(), formId, workflowId));
        assertThat(preview.target().formVersionId()).isEqualTo(first.formVersionId());
        publishTargetSchema();
        long requests = count("apr_requests");
        long commandsBefore = count("apr_draft_commands");

        assertCode(ErrorCode.OBJECT_VERSION_CONFLICT, () -> tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()),
                "stale-target", null)));
        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(commandsBefore);

        var current = tx(() -> drafts.create(currentBody(), "current-source", null));
        var currentPreview = tx(() -> migration.preview(current.requestId(), formId, workflowId));
        assertThat(currentPreview.migrationRequired()).isFalse();
        assertCode(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> migration.migrate(
                current.requestId(), request(current.version(), currentPreview.target()),
                "same-binding", null)));
    }

    @Test
    void wrongOwnerAndCurrentAuthorityFailureStayClosedWithoutWrites() {
        var source = tx(() -> drafts.create(body("Authority source"), "authority-source", null));
        publishTargetSchema();
        var preview = tx(() -> migration.preview(source.requestId(), formId, workflowId));
        long requests = count("apr_requests");
        long receipts = count("apr_draft_commands");

        context(100, true);
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()), "wrong-owner", null)));
        context(99, true);
        var viewOnly = new ApprovalIdentityDirectory.Subject(
                42L, 99L, null, null, "Owner", "owner@example.test", null, "ACTIVE",
                List.of("APPROVAL_OPERATOR"),
                List.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW"));
        when(identities.require(42, 99)).thenReturn(viewOnly);
        assertCode(ErrorCode.FORBIDDEN, () -> tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()), "revoked", null)));
        when(identities.require(42, 99)).thenThrow(
                new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Auth unavailable"));
        assertCode(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> tx(() -> migration.migrate(
                source.requestId(), request(source.version(), preview.target()), "unavailable", null)));

        assertThat(count("apr_requests")).isEqualTo(requests);
        assertThat(count("apr_draft_commands")).isEqualTo(receipts);
    }

    private Target publishTargetSchema() {
        String schema = """
                {"schemaVersion":1,"fields":[
                  {"key":"summary","type":"TEXTAREA","required":true},
                  {"key":"systemName","type":"TEXT","required":true},
                  {"key":"accessRole","type":"SELECT","required":true,"options":["VIEW","EDIT"]},
                  {"key":"startDate","type":"DATE","required":true},
                  {"key":"endDate","type":"DATE","required":true},
                  {"key":"businessJustification","type":"TEXTAREA","required":true}
                ]}
                """;
        UUID version = UUID.randomUUID();
        int number = jdbc.queryForObject(
                "SELECT MAX(version_number)+1 FROM apr_form_versions WHERE tenant_id=42 AND form_id=?",
                Integer.class, formId);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,"
                        + "schema_payload,schema_sha256,lifecycle_state,published_by) "
                        + "VALUES(?,42,?,?,?::jsonb,?,'PUBLISHED',99)",
                version, formId, number, schema, sha(schema));
        jdbc.update("UPDATE apr_forms SET current_version=? WHERE tenant_id=42 AND form_id=?",
                number, formId);
        return new Target(version, number);
    }

    private ApprovalDraftMigrationDtos.MigrateRequest request(
            long version, ApprovalDraftMigrationDtos.Binding target) {
        return new ApprovalDraftMigrationDtos.MigrateRequest(
                version, target.formId(), target.formVersionId(), target.formSchemaSha256(),
                target.workflowId(), target.workflowVersionId(),
                target.workflowDefinitionSha256(), "Migrate retired form safely");
    }

    private ApprovalDtos.CreateRequest currentBody() {
        return new ApprovalDtos.CreateRequest(workflowId, formId, "Already current", "Reason", "NORMAL",
                java.util.Map.of("systemName", "System", "accessRole", "VIEW",
                        "startDate", "2026-09-15", "endDate", "2026-10-15",
                        "businessJustification", "Current governed schema"));
    }

    private UUID formVersion(UUID requestId) {
        return jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE tenant_id=42 AND request_id=?",
                UUID.class, requestId);
    }

    private String payload(UUID requestId) {
        return jdbc.queryForObject("SELECT payload::text FROM apr_request_payloads WHERE tenant_id=42 AND request_id=?",
                String.class, requestId);
    }

    private String route(UUID requestId) {
        return "POST /v1/requests/" + requestId + "/draft/migrate";
    }

    private void assertCode(ErrorCode code, Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Target(UUID formVersionId, int version) {
    }
}
