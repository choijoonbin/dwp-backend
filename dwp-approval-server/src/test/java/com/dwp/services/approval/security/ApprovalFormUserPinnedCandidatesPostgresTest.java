package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormReferenceBindingRepository;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalFormUserBindingRepository;
import com.dwp.services.approval.domain.ApprovalFormUserCandidateService;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormUserPinnedCandidatesPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private ApprovalFormUserCandidateService candidates;
    private ApprovalFormUserDirectory directory;
    private UUID versionId;
    private UUID requestId;
    private String hash;
    private ApprovalDtos.RequestSummary created;

    @BeforeEach void setUp() {
        initialize(POSTGRES);
        var schema = Map.<String, Object>of("schemaContract", "DWP_APPROVAL_FORM_TYPED_V2", "schemaVersion", 2,
                "fields", List.of(field("summary", "TEXTAREA"), field("reviewer", "USER")));
        var compiled = new ApprovalFormSchemaV2Compiler().compile(schema);
        hash = compiled.sha256();
        UUID category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        formId = tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), new ApprovalDtos.CreateFormDraftRequest(
                "PINNED_CANDIDATE_FORM", category, "사용자", "User", "설명", "Description", "APPROVAL_OPERATOR", workflowId, null, schema)));
        context(100, true); ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        tx(() -> { commands.publishForm(ApprovalRequestContext.require(), formId, 0); return null; });
        versionId = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=?", UUID.class, formId);
        context(99, true);
        created = tx(() -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "Pinned request", "Summary", "NORMAL", Map.of()), "pinned-create", "corr"));
        requestId = created.requestId();
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) "
                + "VALUES (?,42,?,2,?::jsonb,?,'DRAFT')", UUID.randomUUID(), formId, compiled.canonicalJson(), compiled.sha256());
        jdbc.update("UPDATE apr_forms SET current_version=2,lifecycle_state='DRAFT' WHERE form_id=?", formId);
        sourcePermissions(true);
        var http = new MockHttpServletRequest(); http.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> requests = mock(ObjectProvider.class);
        when(requests.getIfAvailable()).thenReturn(http);
        String route = ApprovalFormUserCurrentAuthority.WORK_ROUTE;
        ApprovalDecisionRevisionContext.set("psr-" + "b".repeat(64), OffsetDateTime.now().plusSeconds(55), "ctx", "scope", route, "110");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, "DATA", "full-work", true,
                Set.of("predicate.approval.form-published-reference.v1"), null, null, null, false, null, null)));
        var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        directory = mock(ApprovalFormUserDirectory.class);
        when(directory.search(any(), any(), anyInt())).thenAnswer(invocation -> new ApprovalFormUserDirectory.Result(invocation.getArgument(0),
                List.of(new ApprovalFormUserDirectory.Person(42L, 123L, UUID.fromString("12345678-1234-1234-1234-123456789abc"), "Kim", "TENANT", "ACTIVE"))));
        var mapper = new ObjectMapper().findAndRegisterModules();
        candidates = new ApprovalFormUserCandidateService(new ApprovalFormUserBindingRepository(named, mapper),
                new ApprovalFormUserCurrentAuthority(new ApprovalWorkAuthority(identities), identities, requests), directory,
                new ApprovalFormReferenceBindingRepository(named, mapper));
    }

    @AfterEach void tearDown() { clear(); }

    @Test void oldImmutablePublishedRequestAllowsCandidatesAfterCurrentFormAdvancesToDraft() {
        var result = search(requestId, versionId, hash);
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.requestVersion()).isEqualTo(created.version());
        assertThat(result.formVersionId()).isEqualTo(versionId);
        assertThat(result.schemaSha256()).isEqualTo(hash);
        assertThat(result.people()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, requestId)).isEqualTo(versionId);
    }

    @Test void withoutOwnedRequestOldVersionCannotBecomeNewCreateCandidateScope() {
        assertThatThrownBy(() -> search(null, versionId, hash)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), anyInt());
    }

    @Test void foreignOwnerAndAlternateVersionOrHashCannotReachPeopleSource() {
        assertThatThrownBy(() -> search(requestId, UUID.randomUUID(), hash)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> search(requestId, versionId, "a".repeat(64))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_requests SET requester_user_id=100 WHERE request_id=?", requestId);
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), anyInt());
    }

    @Test void deletedOrNonEditableStateAndRetiredFormCannotReachPeopleSource() {
        jdbc.update("UPDATE apr_requests SET status='SUBMITTED' WHERE request_id=?", requestId);
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_requests SET status='DRAFT',deleted_at=CURRENT_TIMESTAMP,deleted_by=99,deletion_reason='test-delete' WHERE request_id=?", requestId);
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_requests SET deleted_at=NULL,deleted_by=NULL,deletion_reason=NULL WHERE request_id=?", requestId);
        jdbc.update("UPDATE apr_forms SET lifecycle_state='RETIRED' WHERE form_id=?", formId);
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), anyInt());
    }

    @Test void requestVersionChangeDuringReadDiscardsAlreadyResolvedPeople() {
        when(directory.search(any(), any(), anyInt())).thenAnswer(invocation -> {
            jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?", requestId);
            return new ApprovalFormUserDirectory.Result(invocation.getArgument(0), List.of());
        });
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test void sourceViewRevocationDuringReadDiscardsPeopleDespiteCurrentCreatePermission() {
        when(directory.search(any(), any(), anyInt())).thenAnswer(invocation -> {
            sourcePermissions(false);
            return new ApprovalFormUserDirectory.Result(invocation.getArgument(0), List.of());
        });
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test void updatePermissionDoesNotImplicitlyGrantCreateReferenceCandidates() {
        var permissions = new java.util.HashSet<>(PERMISSIONS); permissions.add(ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        permissions.remove("ACTION.APPROVAL_REQUEST:CREATE");
        ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, null, null, "Owner", null, null,
                "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)));
        assertThatThrownBy(() -> search(requestId, versionId, hash)).isInstanceOf(BaseException.class);
        verify(directory, never()).search(any(), any(), anyInt());
    }

    private ApprovalFormUserCandidateService.Candidates search(UUID request, UUID version, String sha) {
        return candidates.search(formId, version, sha, null, "reviewer", "Kim", 10, false, request);
    }
    private void sourcePermissions(boolean allowed) {
        var permissions = new java.util.HashSet<>(PERMISSIONS);
        if (allowed) permissions.add(ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, null, null, "Owner", null, null,
                "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)));
    }
    private Map<String, Object> field(String key, String type) { return Map.of("key", key, "labelKo", "항목", "labelEn", "Field", "type", type); }
}
