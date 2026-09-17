package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminMailMemberIdentityTest {

    private AdminMailCompletionRepository repository;
    private MailMemberDirectory directory;
    private AdminMailCompletionService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminMailCompletionRepository.class);
        directory = mock(MailMemberDirectory.class);
        service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                new ObjectMapper().findAndRegisterModules(),
                null, null, null, directory);
    }

    @Test
    void addStoresAuthoritativeIdentityInsteadOfClientSuppliedDisplayFields() {
        UUID inboxId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        SharedInboxMemberRequest request = request(
                11L, "Forged name", "Forged department", key, 3L);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("SHARED_MEMBER_ADD"), eq(key), any(), eq("corr")))
                .thenReturn(true);
        when(repository.sharedInbox(7, inboxId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.SharedInboxRow(
                        inboxId, UUID.randomUUID(), 3L, "DWP_SANDBOX")));
        when(repository.accessGrantByUser(7, inboxId, 11L)).thenReturn(Optional.empty());
        when(directory.requireActive(7, 11)).thenReturn(identity(
                7L, 11L, "Jordan Kim", "People Operations", "ACTIVE"));
        when(repository.bumpSharedInbox(7, inboxId, 3L, 91L)).thenReturn(1);
        when(repository.insertAccessGrant(
                eq(7L), eq(inboxId), eq(11L), any(), any(), any(),
                eq(true), eq(true), eq(false), eq(true), eq(false),
                eq("APPLIED"), eq(91L))).thenReturn(memberId);
        var row = grant(memberId, 11L, "Jordan Kim", "People Operations", 0L);
        when(repository.accessGrant(7, inboxId, memberId)).thenReturn(Optional.of(row));
        when(repository.accessGrants(7, inboxId)).thenReturn(List.of(row));
        when(repository.accessImpact(7, inboxId, null))
                .thenReturn(new AdminMailCompletionRepository.ImpactRow(0, 0, 0));

        SharedInboxAccess result = service.addSharedInboxMember(
                7, 91, inboxId, "corr", request);

        assertThat(result.members().getFirst().displayName()).isEqualTo("Jordan Kim");
        ArgumentCaptor<String> display = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> department = ArgumentCaptor.forClass(String.class);
        verify(repository).insertAccessGrant(
                eq(7L), eq(inboxId), eq(11L), display.capture(), department.capture(), any(),
                eq(true), eq(true), eq(false), eq(true), eq(false),
                eq("APPLIED"), eq(91L));
        assertThat(display.getValue()).isEqualTo("Jordan Kim");
        assertThat(department.getValue()).isEqualTo("People Operations");
    }

    @Test
    void updateRejectsAnInactiveAuthoritativeIdentityBeforeMutation() {
        UUID inboxId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        SharedInboxMemberRequest request = request(
                11L, "Client name", "Client department", UUID.randomUUID(), 2L);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("SHARED_MEMBER_UPDATE"), any(), any(), eq("corr")))
                .thenReturn(true);
        when(repository.sharedInbox(7, inboxId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.SharedInboxRow(
                        inboxId, UUID.randomUUID(), 4L, "DWP_SANDBOX")));
        when(repository.accessGrant(7, inboxId, memberId)).thenReturn(Optional.of(
                grant(memberId, 11L, "Old name", "Old department", 2L)));
        when(directory.requireActive(7, 11)).thenReturn(identity(
                7L, 11L, "Inactive user", "People Operations", "INACTIVE"));

        assertThatThrownBy(() -> service.updateSharedInboxMember(
                7, 91, inboxId, memberId, "corr", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SHARED_MEMBER_USER_NOT_ACTIVE");
        verify(repository, never()).updateAccessGrant(
                anyLong(), any(), any(), any(), any(), any(),
                eq(true), eq(true), eq(false), eq(true), eq(false),
                any(), anyLong(), anyLong());
    }

    @Test
    void crossTenantIdentityFailsClosed() {
        UUID inboxId = UUID.randomUUID();
        SharedInboxMemberRequest request = request(
                11L, null, null, UUID.randomUUID(), 0L);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("SHARED_MEMBER_ADD"), any(), any(), eq("corr")))
                .thenReturn(true);
        when(repository.sharedInbox(7, inboxId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.SharedInboxRow(
                        inboxId, UUID.randomUUID(), 0L, "DWP_SANDBOX")));
        when(repository.accessGrantByUser(7, inboxId, 11L)).thenReturn(Optional.empty());
        when(directory.requireActive(7, 11)).thenReturn(identity(
                8L, 11L, "Wrong tenant", null, "ACTIVE"));

        assertThatThrownBy(() -> service.addSharedInboxMember(
                7, 91, inboxId, "corr", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SHARED_MEMBER_USER_NOT_ACTIVE");
        verify(repository, never()).insertAccessGrant(
                anyLong(), any(), anyLong(), any(), any(), any(),
                eq(true), eq(true), eq(false), eq(true), eq(false), any(), anyLong());
    }

    @Test
    void duplicateMembershipIsRejectedWithoutAnotherDirectoryLookup() {
        UUID inboxId = UUID.randomUUID();
        SharedInboxMemberRequest request = request(
                11L, null, null, UUID.randomUUID(), 0L);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("SHARED_MEMBER_ADD"), any(), any(), eq("corr")))
                .thenReturn(true);
        when(repository.sharedInbox(7, inboxId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.SharedInboxRow(
                        inboxId, UUID.randomUUID(), 0L, "DWP_SANDBOX")));
        when(repository.accessGrantByUser(7, inboxId, 11L)).thenReturn(Optional.of(
                grant(UUID.randomUUID(), 11L, "Existing", null, 0L)));

        assertThatThrownBy(() -> service.addSharedInboxMember(
                7, 91, inboxId, "corr", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SHARED_MEMBER_ALREADY_EXISTS");
        verifyNoInteractions(directory);
    }

    private SharedInboxMemberRequest request(
            long userId, String displayName, String department, UUID key, long version) {
        return new SharedInboxMemberRequest(
                userId, displayName, department,
                new AccessPermissions(true, true, false, true, false),
                null, false, key, version);
    }

    private MailMemberDirectory.MemberIdentity identity(
            long tenantId, long userId, String name, String department, String status) {
        return new MailMemberDirectory.MemberIdentity(
                tenantId, userId, name, department, "member@example.com", status, "TENANT");
    }

    private AdminMailCompletionRepository.AccessGrantRow grant(
            UUID memberId, long userId, String name, String department, long version) {
        return new AdminMailCompletionRepository.AccessGrantRow(
                memberId, userId, name, department, "ACTIVE", null,
                true, true, false, true, false, "APPLIED", version);
    }
}
