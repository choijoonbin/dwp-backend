package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private MailQueryRepository queries;
    @Mock
    private MailCommandRepository commands;
    @Mock
    private MailProviderCatalog providerCatalog;

    private MailService service;

    @BeforeEach
    void setUp() {
        service = new MailService(queries, commands, providerCatalog);
    }

    @Test
    void acceptedAiProposalPublishesAGovernedActionWithoutExecutingIt() {
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        MailDtos.ActionProposal before = proposal(
                proposalId, threadId, ProposalStatus.PROPOSED, 2L);
        MailDtos.ActionProposal after = proposal(
                proposalId, threadId, ProposalStatus.ACCEPTED, 3L);
        when(queries.proposal(1L, 7L, proposalId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(queries.policy(1L)).thenReturn(policy());
        when(commands.decideProposal(
                1L, 7L, proposalId, ProposalDecision.ACCEPT, 2L)).thenReturn(1);

        MailDtos.ActionProposal result = service.decideProposal(
                1L, 7L, "APP.CALENDAR:CREATE", proposalId, "corr-ai",
                new MailDtos.ProposalDecisionRequest(ProposalDecision.ACCEPT, 2L));

        assertThat(result.status()).isEqualTo(ProposalStatus.ACCEPTED);
        verify(commands).audit(
                eq(1L), eq(7L), eq("mail.action.accepted"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-ai"), anyMap(), anyMap());
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_ACTION_PROPOSAL"), eq(proposalId),
                eq("mail.action.accepted"), anyMap(), eq("corr-ai"));
    }

    @Test
    void aiProposalCannotBeAcceptedOutsideCurrentTargetPermissionScope() {
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        when(queries.proposal(1L, 7L, proposalId)).thenReturn(Optional.of(
                proposal(proposalId, threadId, ProposalStatus.PROPOSED, 2L)));
        when(queries.policy(1L)).thenReturn(policy());

        assertThatThrownBy(() -> service.decideProposal(
                1L, 7L, "APP.MAIL:UPDATE", proposalId, "corr-ai-denied",
                new MailDtos.ProposalDecisionRequest(ProposalDecision.ACCEPT, 2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("permission scope");
    }

    @Test
    void externalConnectionCannotActivateWithoutVaultedCredentialReference() {
        UUID connectionId = UUID.randomUUID();
        when(queries.connection(1L, connectionId)).thenReturn(Optional.of(
                new MailDtos.ConnectionSummary(
                        connectionId, "microsoft-graph", "Microsoft 365",
                        ProviderType.MICROSOFT_GRAPH, "OAUTH2", "sk.com",
                        ConnectionState.CONFIGURATION_REQUIRED,
                        List.of("READ", "SEND"), false,
                        null, null, 0L)));

        assertThatThrownBy(() -> service.updateConnection(
                1L, 10L, connectionId, "corr-connection",
                new MailDtos.ConnectionUpdateRequest(
                        "Microsoft 365", "sk.com", null,
                        ConnectionState.ACTIVE, 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("secret-store reference");
    }

    @Test
    void threadMutationUsesOptimisticVersionAndProducesAuditEvidence() {
        UUID threadId = UUID.randomUUID();
        MailDtos.ThreadSummary before = thread(threadId, true, 4L);
        MailDtos.ThreadSummary after = thread(threadId, false, 5L);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(commands.applyAction(
                1L, 7L, threadId, ThreadAction.MARK_READ, 4L)).thenReturn(1);
        when(queries.messages(1L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.applyAction(
                1L, 7L, threadId, "corr-thread",
                new MailDtos.ThreadActionRequest(ThreadAction.MARK_READ, 4L));

        assertThat(result.thread().unread()).isFalse();
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_THREAD"), eq(threadId),
                eq("mail.thread.mark.read"), anyMap(), eq("corr-thread"));
    }

    @Test
    void sharedInboxAssignmentRejectsUsersOutsideTheInboxMembership() {
        UUID threadId = UUID.randomUUID();
        UUID sharedInboxId = UUID.randomUUID();
        MailDtos.ThreadSummary sharedThread = new MailDtos.ThreadSummary(
                threadId, UUID.randomUUID(), "People Help", "INBOX",
                sharedInboxId, "People Help", "문의", "확인 부탁드립니다.",
                List.of(new MailDtos.Participant("구성원", "member@sk.com")),
                OffsetDateTime.now(), true, false, Importance.HIGH,
                TriageLane.ASSIGNED, WorkflowState.OPEN, null,
                null, null, false, false, Classification.INTERNAL, 1, 0L);
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(sharedThread));
        when(queries.isActiveSharedInboxMember(1L, sharedInboxId, 99L)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(
                1L, 7L, threadId, "corr-assign",
                new MailDtos.AssignRequest(99L, "비구성원", 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not an active member");
    }

    @Test
    void sharedInboxUpdateUsesTenantScopedVersionAndWritesAuditEvidence() {
        UUID sharedInboxId = UUID.randomUUID();
        MailDtos.SharedInboxSummary before = sharedInbox(sharedInboxId, "People Help", 240, 2L);
        MailDtos.SharedInboxSummary after = sharedInbox(sharedInboxId, "People Care", 120, 3L);
        when(queries.sharedInbox(1L, sharedInboxId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        MailDtos.SharedInboxUpdateRequest request = new MailDtos.SharedInboxUpdateRequest(
                "People Care", "구성원 문의를 함께 처리합니다.", 120, "ACTIVE", 2L);
        when(commands.updateSharedInbox(1L, 7L, sharedInboxId, request)).thenReturn(1);

        MailDtos.SharedInboxSummary result = service.updateSharedInbox(
                1L, 7L, sharedInboxId, "corr-shared", request);

        assertThat(result.displayName()).isEqualTo("People Care");
        assertThat(result.serviceTargetMinutes()).isEqualTo(120);
        verify(commands).audit(
                eq(1L), eq(7L), eq("mail.shared.inbox.updated"),
                eq("MAIL_SHARED_INBOX"), eq(sharedInboxId.toString()),
                eq("corr-shared"), anyMap(), anyMap());
    }

    private MailDtos.ActionProposal proposal(
            UUID proposalId,
            UUID threadId,
            ProposalStatus status,
            long version) {
        return new MailDtos.ActionProposal(
                proposalId, threadId, ProposalType.CREATE_CALENDAR_EVENT,
                1, status, "일정 제안", "메일에서 일정을 발견했습니다.",
                List.of(Map.of("messageId", "message-1")),
                Map.of(
                        "durationMinutes", 30,
                        "timeZone", "Asia/Seoul",
                        "requiresConfirmation", true),
                new BigDecimal("0.9100"), "MEDIUM",
                "APP.CALENDAR", "CREATE", "/calendar/schedule?action=create",
                OffsetDateTime.now().plusDays(1), version);
    }

    private MailDtos.TenantPolicy policy() {
        return new MailDtos.TenantPolicy(
                true, true, true, true, true, false, 365, 25, 0L);
    }

    private MailDtos.SharedInboxSummary sharedInbox(
            UUID id, String name, int serviceTargetMinutes, long version) {
        return new MailDtos.SharedInboxSummary(
                id, "people-help", name, "people-help@sk.com",
                "구성원 문의를 함께 처리합니다.", serviceTargetMinutes,
                "ACTIVE", 4, 1, version);
    }

    private MailDtos.ThreadSummary thread(UUID threadId, boolean unread, long version) {
        return new MailDtos.ThreadSummary(
                threadId, UUID.randomUUID(), "내 메일", "INBOX", null, null,
                "고객 검토 요청", "내일 회의 전에 확인해 주세요.",
                List.of(new MailDtos.Participant("고객", "customer@example.com")),
                OffsetDateTime.now(), unread, false, Importance.HIGH,
                TriageLane.NEEDS_REPLY, WorkflowState.OPEN, null,
                null, null, true, true, Classification.CONFIDENTIAL,
                1, version);
    }
}
