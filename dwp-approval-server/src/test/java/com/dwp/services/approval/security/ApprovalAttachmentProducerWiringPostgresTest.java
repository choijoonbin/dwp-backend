package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.attachment.ApprovalAttachmentProviderGate;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.domain.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true"})
class ApprovalAttachmentProducerWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_attachment_producer_wiring").withLabel("dwp.approval.owner", "cicero-abc-producer");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    static final Set<String> PERMISSIONS = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE",
            "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_TASK:VIEW");
    @Autowired ApprovalCommandRepository commands;
    @Autowired ApprovalQueryRepository queries;
    @Autowired ApprovalDraftService drafts;
    @Autowired ApprovalService approvals;
    @Autowired ApprovalAttachmentLifecycleBinding binding;
    @Autowired ApprovalAttachmentProviderGate providers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @MockitoBean ApprovalIdentityDirectory identities;
    UUID workflow, form;
    final AtomicLong revokeAtHistoryCount = new AtomicLong(Long.MAX_VALUE);

    @BeforeEach void prepare() {
        clear(); revokeAtHistoryCount.set(Long.MAX_VALUE);
        when(identities.require(42, 99)).thenAnswer(ignored -> subject(
                count("apr_request_payload_versions") >= revokeAtHistoryCount.get() ? Set.of() : PERMISSIONS));
        when(identities.requireRole(anyLong(), anyString())).thenAnswer(call ->
                new ApprovalIdentityDirectory.RoleEligibility(call.getArgument(0), call.getArgument(1), "ACTIVE", 1, true));
        queries.ensureTenant(42);
        workflow = jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
        form = jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=42 AND form_key='ACCESS_EXCEPTION_FORM'", UUID.class);
        context(false);
    }
    @AfterEach void cleanup() { clear(); }

    @Test void actualWebBootInjectsRealBindingThroughInheritedPackagePrivateSetter() {
        Object target = AopProxyUtils.getSingletonTarget(commands);
        assertThat(ReflectionTestUtils.getField(target == null ? commands : target, "attachmentBinding")).isSameAs(binding);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void disabledProvidersAndAbsentAttachmentPolicyPreserveActualCreateSaveRecoverSubmit() {
        assertThat(providers.readiness()).isEqualTo("NOT_CONFIGURED");
        long uploads = count("apr_attachment_uploads"), policies = count("apr_attachment_policy_heads");
        var first = drafts.create(body("Initial empty"), key(), null);
        var saved = drafts.update(first.requestId(), update("Edited empty", 0), key(), null);
        context(true);
        var recovered = drafts.recover(first.requestId(), new ApprovalWorkDtos.RecoverDraft(1, saved.request().version(), key(), "Restore first revision"), null);
        assertThat(recovered.payloadRevision()).isEqualTo(3);
        assertThat(recovered.version()).isEqualTo(2);
        context(false);
        approvals.submit(first.requestId(), recovered.version(), null);
        assertThat(jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?", String.class, first.requestId())).isEqualTo("IN_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_manifests WHERE request_id=?", Long.class, first.requestId())).isEqualTo(3);
        assertThat(count("apr_attachment_uploads")).isEqualTo(uploads);
        assertThat(count("apr_attachment_policy_heads")).isEqualTo(policies);
    }

    @Test void createAuthorityRevokedAfterImmutableHistoryRollsBackRequestReceiptSealAndEvents() {
        var before = snapshot(); revokeAtHistoryCount.set(count("apr_request_payload_versions") + 1);
        forbidden(() -> drafts.create(body("Revoke after history"), key(), null));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void saveAuthorityRevokedBeforeSealRollsBackPayloadCasHistoryAuditOutboxAndReceipt() {
        var first = drafts.create(body("Before denied save"), key(), null);
        var before = snapshot(); String hash = payloadHash(first.requestId());
        revokeAtHistoryCount.set(count("apr_request_payload_versions") + 1);
        forbidden(() -> drafts.update(first.requestId(), update("Denied save", 0), key(), null));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(payloadHash(first.requestId())).isEqualTo(hash);
    }

    @Test void recoveryAuthorityRevokedBeforeSealRollsBackHistoricalSelectionAndReceipt() {
        var first = drafts.create(body("Recover original"), key(), null);
        drafts.update(first.requestId(), update("Current revision", 0), key(), null);
        context(true); var before = snapshot(); String hash = payloadHash(first.requestId());
        revokeAtHistoryCount.set(count("apr_request_payload_versions") + 1);
        forbidden(() -> drafts.recover(first.requestId(), new ApprovalWorkDtos.RecoverDraft(1, 1L, key(), "Denied recovery"), null));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(payloadHash(first.requestId())).isEqualTo(hash);
    }

    private ApprovalDtos.CreateRequest body(String title) { return new ApprovalDtos.CreateRequest(workflow, form, title, "Reason", "NORMAL", payload()); }
    private ApprovalDtos.UpdateDraftRequest update(String title, long version) { return new ApprovalDtos.UpdateDraftRequest(workflow, form, title, "Reason", "NORMAL", payload(), version); }
    private Map<String, Object> payload() { return Map.of("systemName", "System", "accessRole", "VIEW", "startDate", "2026-09-15",
            "endDate", "2026-10-15", "compensatingControl", "Daily access review"); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private String payloadHash(UUID id) { return jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, id); }
    private Map<String, Long> snapshot() { return List.of("apr_requests", "apr_request_payloads", "apr_request_payload_versions", "apr_draft_commands",
            "apr_attachment_manifests", "apr_attachment_selections", "apr_request_events", "apr_integration_outbox", "sys_audit_outbox")
            .stream().collect(java.util.stream.Collectors.toMap(table -> table, this::count)); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static ApprovalIdentityDirectory.Subject subject(Set<String> permissions) { return new ApprovalIdentityDirectory.Subject(42L, 99L,
            null, null, "Owner", "owner@example.test", null, "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)); }
    private static void context(boolean recover) {
        clear(); ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), PERMISSIONS);
        if (recover) {
            ApprovalDecisionRevisionContext.set("rev1", java.time.OffsetDateTime.now().plusMinutes(5), "work", "own",
                    "route.approvals.work.request-draft-recover.action", "110");
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                    "route.approvals.work.request-draft-recover.action", "ACTION", "owner", false,
                    Set.of("predicate.approval.own-request.v1"), null, null, null, false, null, null)));
        }
    }
    private static void clear() { ApprovalRequestContext.clear(); ApprovalDecisionRevisionContext.clear(); ApprovalPilotAuthorizationContext.clear(); ApprovalManagementScopeContext.clear(); }
    private static void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable body) { assertThatThrownBy(body)
            .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)); }
}
