package com.dwp.services.space.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.operations.SpaceOperationsService;
import com.dwp.services.space.integration.SpaceEntitlementPort;
import com.dwp.services.space.security.SpaceRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock
    private SpaceQueryRepository queries;
    @Mock
    private SpaceCommandRepository commands;
    @Mock
    private SpaceTemplateCommandRepository templateCommands;
    @Mock
    private SpaceOwnerRecoveryRepository ownerRecovery;
    @Mock
    private AuditOutboxRecorder audit;
    @Mock
    private SpaceOperationsService operations;
    @Mock
    private SpaceEntitlementPort entitlements;

    @AfterEach
    void clearRequestContext() {
        SpaceRequestContext.clear();
    }

    @Test
    void requestOnlyPreviewDoesNotExposeSpaceBody() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));
        UUID spaceId = UUID.randomUUID();
        SpaceDtos.SpaceSummary summary = summary(spaceId, "REQUEST", null);
        when(queries.space(SpaceRequestContext.get(), "governed-space")).thenReturn(summary);
        when(queries.canManage(SpaceRequestContext.get(), spaceId)).thenReturn(false);
        when(queries.policies(1L, spaceId)).thenReturn(policies());

        SpaceDtos.SpaceDetail detail = service().space("governed-space");

        assertThat(detail.canContribute()).isFalse();
        assertThat(detail.featuredContent()).isEmpty();
        assertThat(detail.apps()).isEmpty();
        assertThat(detail.activity()).isEmpty();
        verify(queries, never()).content(1L, spaceId, false, 20);
        verify(queries, never()).apps(1L, spaceId);
        verify(queries, never()).activity(1L, spaceId, 20);
    }

    @Test
    void openPreviewLoadsPublishedBodyWithoutContributionRights() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));
        UUID spaceId = UUID.randomUUID();
        SpaceDtos.SpaceSummary summary = summary(spaceId, "OPEN", null);
        when(queries.space(SpaceRequestContext.get(), "open-space")).thenReturn(summary);
        when(queries.canManage(SpaceRequestContext.get(), spaceId)).thenReturn(false);
        when(queries.policies(1L, spaceId)).thenReturn(policies());
        when(queries.content(1L, spaceId, false, 20)).thenReturn(List.of());
        when(queries.apps(1L, spaceId)).thenReturn(List.of());
        when(queries.activity(1L, spaceId, 20)).thenReturn(List.of());

        SpaceDtos.SpaceDetail detail = service().space("open-space");

        assertThat(detail.canContribute()).isFalse();
        verify(queries).content(1L, spaceId, false, 20);
        verify(queries).apps(1L, spaceId);
        verify(queries).activity(1L, spaceId, 20);
    }

    @Test
    void administrationRequiresDedicatedSpacePermission() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));

        assertThatThrownBy(() -> service().adminOverview()).isInstanceOf(BaseException.class);

        verify(queries, never()).adminMetrics(1L);
    }

    @Test
    void moderatorCanInspectMembershipsButCannotChangeThem() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));
        UUID spaceId = UUID.randomUUID();
        SpaceDtos.SpaceSummary summary = summary(spaceId, "HIDDEN", "MODERATOR");
        when(queries.space(SpaceRequestContext.get(), "governed-space")).thenReturn(summary);
        when(queries.canModerate(SpaceRequestContext.get(), spaceId)).thenReturn(true);
        when(queries.canManage(SpaceRequestContext.get(), spaceId)).thenReturn(false);
        when(queries.policies(1L, spaceId)).thenReturn(policies());
        when(queries.content(1L, spaceId, true, 20)).thenReturn(List.of());
        when(queries.apps(1L, spaceId)).thenReturn(List.of());
        when(queries.activity(1L, spaceId, 20)).thenReturn(List.of());
        when(queries.members(1L, spaceId)).thenReturn(List.of());

        SpaceDtos.SpaceDetail detail = service().space("governed-space");

        assertThat(detail.canModerate()).isTrue();
        assertThat(detail.canManage()).isFalse();
        assertThat(service().members("governed-space")).isEmpty();
        assertThatThrownBy(() -> service().saveMember(
                "governed-space",
                new SpaceDtos.SaveMemberRequest("USER", "person-42", "VIEWER", null),
                "corr-member"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void tenantGovernanceResponsibilityDoesNotGrantSpaceContentAccess() {
        SpaceRequestContext.set(subject(Set.of("ADMIN.SPACE_GOVERNANCE:MANAGE")));
        UUID spaceId = UUID.randomUUID();
        SpaceDtos.SpaceSummary summary = summary(spaceId, "HIDDEN", null);
        when(queries.space(SpaceRequestContext.get(), "governed-space")).thenReturn(summary);
        when(queries.policies(1L, spaceId)).thenReturn(policies());

        SpaceDtos.SpaceDetail detail = service().space("governed-space");

        assertThat(detail.canModerate()).isFalse();
        assertThat(detail.canManage()).isFalse();
        assertThat(detail.featuredContent()).isEmpty();
        verify(queries, never()).content(1L, spaceId, false, 20);
    }

    @Test
    void ownerRecoveryValidatesIdentityAndCreatesAuditedOwnerMembership() {
        SpaceRequestContext.set(subject(Set.of("ADMIN.SPACE_GOVERNANCE:MANAGE")));
        UUID spaceId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        SpaceDtos.SpaceSummary summary = summary(spaceId, "HIDDEN", null);
        when(queries.adminSpace(SpaceRequestContext.get(), "governed-space")).thenReturn(summary);
        when(queries.hasActiveOwner(1L, spaceId)).thenReturn(false);
        when(queries.members(1L, spaceId)).thenReturn(List.of());

        service().recoverOwner(
                "governed-space",
                new SpaceDtos.RecoverOwnerRequest(personId, "Restore accountable ownership."),
                "corr-recovery");

        verify(entitlements).validatePrincipal(any(SpaceEntitlementPort.ValidationCommand.class));
        verify(ownerRecovery).recover(SpaceRequestContext.get(), spaceId, personId);
        verify(operations).recordPolicyEvaluation(
                org.mockito.ArgumentMatchers.eq(SpaceRequestContext.get()),
                org.mockito.ArgumentMatchers.eq(spaceId),
                org.mockito.ArgumentMatchers.eq("SPACE_OWNER_RECOVERY"),
                org.mockito.ArgumentMatchers.eq("SPACE"),
                org.mockito.ArgumentMatchers.eq(spaceId.toString()),
                org.mockito.ArgumentMatchers.eq("ALLOW"),
                org.mockito.ArgumentMatchers.eq("ADMIN_RECOVERY"),
                org.mockito.ArgumentMatchers.eq("CRITICAL"),
                org.mockito.ArgumentMatchers.eq("corr-recovery"),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void ownerRecoveryIsRejectedWhenAnActiveOwnerAlreadyExists() {
        SpaceRequestContext.set(subject(Set.of("ADMIN.SPACE_GOVERNANCE:MANAGE")));
        UUID spaceId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        when(queries.adminSpace(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(spaceId, "HIDDEN", null));
        when(queries.hasActiveOwner(1L, spaceId)).thenReturn(true);

        assertThatThrownBy(() -> service().recoverOwner(
                "governed-space",
                new SpaceDtos.RecoverOwnerRequest(personId, "Replace the current owner."),
                "corr-conflict"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT));

        verify(entitlements, never()).validatePrincipal(any());
        verify(ownerRecovery, never()).recover(any(), any(), any());
    }

    @Test
    void accessRequestRequiresTheDedicatedMembershipPermission() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));

        assertThatThrownBy(() -> service().requestAccess("governed-space", accessRequest(), "corr-access"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(queries, never()).space(any(), any());
        verify(commands, never()).createAccessRequest(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "HIDDEN"})
    void restrictedSpacesDoNotAcceptSelfServiceMembershipRequests(String visibility) {
        SpaceRequestContext.set(subject(Set.of("ACTION.SPACE_MEMBERSHIP:CREATE")));
        when(queries.space(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(UUID.randomUUID(), visibility, null));

        assertThatThrownBy(() -> service().requestAccess("governed-space", accessRequest(), "corr-access"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(commands, never()).createAccessRequest(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "SUSPENDED", "DELETED"})
    void inactiveSpacesDoNotAcceptMembershipRequests(String lifecycleState) {
        SpaceRequestContext.set(subject(Set.of("ACTION.SPACE_MEMBERSHIP:CREATE")));
        when(queries.space(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(UUID.randomUUID(), "REQUEST", null, lifecycleState));

        assertThatThrownBy(() -> service().requestAccess("governed-space", accessRequest(), "corr-access"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(commands, never()).createAccessRequest(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "CONTRIBUTOR", "EDITOR", "MODERATOR", "OWNER", "GUEST"})
    void existingEffectiveRolesCannotCreateDuplicateMembershipRequests(String role) {
        SpaceRequestContext.set(subject(Set.of("ACTION.SPACE_MEMBERSHIP:CREATE")));
        when(queries.space(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(UUID.randomUUID(), "REQUEST", role));

        assertThatThrownBy(() -> service().requestAccess("governed-space", accessRequest(), "corr-access"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT));

        verify(commands, never()).createAccessRequest(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "REQUEST"})
    void eligibleNonmembersCanRequestAccessToActiveDiscoverableSpaces(String visibility) {
        SpaceRequestContext.Subject actor = subject(Set.of("ACTION.SPACE_MEMBERSHIP:CREATE"));
        SpaceRequestContext.set(actor);
        UUID spaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        SpaceDtos.CreateAccessRequest input = accessRequest();
        SpaceDtos.AccessRequestSummary created = new SpaceDtos.AccessRequestSummary(
                requestId, spaceId, "governed-space", "Space", "Space", actor.userId(),
                actor.displayName(), "VIEWER", input.justification(), "OWNER_REVIEW",
                "PENDING", null, Instant.now(), null, 0L);
        when(queries.space(actor, "governed-space")).thenReturn(summary(spaceId, visibility, null));
        when(commands.createAccessRequest(actor, spaceId, input)).thenReturn(requestId);
        when(queries.accessRequests(actor.tenantId(), null, null, "ALL")).thenReturn(List.of(created));

        assertThat(service().requestAccess("governed-space", input, "corr-access")).isEqualTo(created);

        verify(commands).createAccessRequest(actor, spaceId, input);
    }

    @Test
    void openSpaceNonmemberReceivesForbiddenInsteadOfNullRoleFailureWhenCreatingContent() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW", "ACTION.SPACE_CONTENT:CREATE")));
        when(queries.space(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(UUID.randomUUID(), "OPEN", null));
        SpaceDtos.CreateContentRequest input = new SpaceDtos.CreateContentRequest(
                "POST", "New content", "Summary", "INTERNAL", Map.of("body", "Test"));

        assertThatThrownBy(() -> service().createContent("governed-space", input, "corr-content"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(commands, never()).createContent(any(), any(), any());
    }

    @Test
    void archivedSpaceMembersCannotContribute() {
        SpaceRequestContext.set(subject(Set.of("APP.SPACES:VIEW")));
        UUID spaceId = UUID.randomUUID();
        when(queries.space(SpaceRequestContext.get(), "governed-space"))
                .thenReturn(summary(spaceId, "OPEN", "CONTRIBUTOR", "ARCHIVED"));
        when(queries.policies(1L, spaceId)).thenReturn(policies());

        assertThat(service().space("governed-space").canContribute()).isFalse();
    }

    @Test
    void managerWritesRecheckAuthorityAfterAcquiringTheSpaceLock() {
        SpaceRequestContext.Subject actor = subject(Set.of("APP.SPACES:VIEW"));
        SpaceRequestContext.set(actor);
        UUID spaceId = UUID.randomUUID();
        when(queries.space(actor, "governed-space"))
                .thenReturn(summary(spaceId, "OPEN", "OWNER"), summary(spaceId, "OPEN", null));

        assertThatThrownBy(() -> service().saveMember("governed-space",
                new SpaceDtos.SaveMemberRequest("USER", "42", "VIEWER", null), "corr-member"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        var order = inOrder(queries, commands);
        order.verify(queries).space(actor, "governed-space");
        order.verify(commands).lockMembershipScope(actor, spaceId);
        order.verify(queries).space(actor, "governed-space");
        order.verify(queries).canManage(actor, spaceId);
        verify(commands, never()).saveMember(any(), any(), any());
    }

    @Test
    void contentWritesUseTheRoleRefreshedAfterTheSpaceLock() {
        SpaceRequestContext.Subject actor = subject(Set.of("APP.SPACES:VIEW"));
        SpaceRequestContext.set(actor);
        UUID spaceId = UUID.randomUUID();
        when(queries.space(actor, "governed-space"))
                .thenReturn(summary(spaceId, "OPEN", "CONTRIBUTOR"), summary(spaceId, "OPEN", "VIEWER"));
        SpaceDtos.CreateContentRequest input = new SpaceDtos.CreateContentRequest(
                "POST", "New content", "Summary", "INTERNAL", Map.of("body", "Test"));

        assertThatThrownBy(() -> service().createContent("governed-space", input, "corr-content"))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(commands).lockMembershipScope(actor, spaceId);
        verify(commands, never()).createContent(any(), any(), any());
    }

    private SpaceDtos.CreateAccessRequest accessRequest() {
        return new SpaceDtos.CreateAccessRequest("VIEWER", "Need access for project collaboration.");
    }

    private SpaceService service() {
        return new SpaceService(
                queries, commands, templateCommands, ownerRecovery, audit, operations, entitlements);
    }

    private SpaceRequestContext.Subject subject(Set<String> permissions) {
        return new SpaceRequestContext.Subject(
                100L,
                1L,
                UUID.randomUUID(),
                "Test User",
                Set.of("WORKSPACE_MEMBER"),
                permissions,
                Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private SpaceDtos.SpaceSummary summary(UUID id, String visibility, String role) {
        return summary(id, visibility, role, "ACTIVE");
    }

    private SpaceDtos.SpaceSummary summary(UUID id, String visibility, String role, String lifecycleState) {
        return new SpaceDtos.SpaceSummary(
                id,
                "governed-space",
                "검증 Space",
                "Governed Space",
                "정책 검증",
                "Policy verification",
                "PROJECT",
                visibility,
                "INTERNAL",
                role,
                10,
                2,
                0,
                "layers-3",
                "indigo",
                null,
                lifecycleState,
                Instant.now(),
                0L);
    }

    private Map<String, String> policies() {
        return Map.of(
                "contentPolicy", "OWNER_REVIEW",
                "appPolicy", "OWNER_REVIEW",
                "aiPolicy", "MEMBER_SCOPED");
    }
}
