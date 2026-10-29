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
class MailServiceProposalTest {

    @Mock
    private MailQueryRepository queries;
    @Mock
    private MailCommandRepository commands;
    @Mock
    private MailProviderCatalog providerCatalog;
    @Mock
    private MailDeliveryCompletionService deliveryCompletion;
    @Mock
    private MailNotificationEvents notificationEvents;
    @Mock
    private MailWorkspaceRepository workspaceRepository;
    @Mock
    private MailAdminMutationReceipts adminReceipts;
    @Mock
    private MailBulkMutationReceipts bulkMutationReceipts;

    private MailService service;

    @BeforeEach
    void setUp() {
        service = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                workspaceRepository, null, adminReceipts, bulkMutationReceipts);
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
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, true, 0L)));
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
    void acceptedNotificationEscalationIsOwnedAndCompletedByNotificationEvidence() {
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID notificationEventId = UUID.randomUUID();
        MailDtos.ActionProposal before = escalationProposal(
                proposalId, threadId, ProposalStatus.PROPOSED, 2L);
        MailDtos.ActionProposal accepted = escalationProposal(
                proposalId, threadId, ProposalStatus.ACCEPTED, 3L);
        MailDtos.ActionProposal executed = escalationProposal(
                proposalId, threadId, ProposalStatus.EXECUTED, 4L);
        when(queries.proposal(1L, 7L, proposalId))
                .thenReturn(Optional.of(before), Optional.of(accepted), Optional.of(executed));
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, true, 0L)));
        when(queries.policy(1L)).thenReturn(policy());
        when(commands.decideProposal(
                1L, 7L, proposalId, ProposalDecision.ACCEPT, 2L)).thenReturn(1);
        when(notificationEvents.actionEscalated(
                1L, 7L, accepted, "corr-notify")).thenReturn(notificationEventId);
        when(queries.ownerProposalHandoff(1L, proposalId, true)).thenReturn(Optional.of(
                new MailQueryRepository.OwnerProposalHandoffRow(
                        proposalId, commandId, ProposalType.ESCALATE_NOTIFICATION,
                        threadId, accepted.proposedPayload(), 7L, accepted.targetRoute(),
                        "ACCEPTED", null, OffsetDateTime.now(), 3L)));
        when(commands.updateProposalOutcomeFromOwner(
                1L, 7L, proposalId, commandId,
                ProposalType.ESCALATE_NOTIFICATION, "EXECUTED",
                "notification-event:" + notificationEventId, 3L)).thenReturn(1);

        MailDtos.ActionProposal result = service.decideProposal(
                1L, 7L, "APP.MAIL:UPDATE", proposalId, "corr-notify",
                new MailDtos.ProposalDecisionRequest(ProposalDecision.ACCEPT, 2L));

        assertThat(result.status()).isEqualTo(ProposalStatus.EXECUTED);
        verify(notificationEvents).actionEscalated(1L, 7L, accepted, "corr-notify");
        verify(commands).updateProposalOutcomeFromOwner(
                1L, 7L, proposalId, commandId,
                ProposalType.ESCALATE_NOTIFICATION, "EXECUTED",
                "notification-event:" + notificationEventId, 3L);
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_ACTION_PROPOSAL"), eq(proposalId),
                eq("mail.action.owner-outcome"), anyMap(), eq("corr-notify"));
    }

    @Test
    void aiProposalCannotBeAcceptedOutsideCurrentTargetPermissionScope() {
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        when(queries.proposal(1L, 7L, proposalId)).thenReturn(Optional.of(
                proposal(proposalId, threadId, ProposalStatus.PROPOSED, 2L)));
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, true, 0L)));
        when(queries.policy(1L)).thenReturn(policy());

        assertThatThrownBy(() -> service.decideProposal(
                1L, 7L, "APP.MAIL:UPDATE", proposalId, "corr-ai-denied",
                new MailDtos.ProposalDecisionRequest(ProposalDecision.ACCEPT, 2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("permission scope");
    }

    @Test
    void proposedActionPayloadCanBeEditedWithOptimisticVersioning() {
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        MailDtos.ActionProposal before = proposal(
                proposalId, threadId, ProposalStatus.PROPOSED, 2L);
        Map<String, Object> revisedPayload = Map.of(
                "requiresConfirmation", true,
                "durationMinutes", 45,
                "title", "Updated review");
        MailDtos.ActionProposal after = new MailDtos.ActionProposal(
                before.proposalId(), before.threadId(), before.type(),
                before.actionContractVersion(), before.status(), before.title(),
                before.summary(), before.evidence(), revisedPayload,
                before.confidence(), before.riskLevel(), before.requiredResourceKey(),
                before.requiredPermissionCode(), before.targetRoute(), before.expiresAt(), 3L);
        when(queries.proposal(1L, 7L, proposalId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 0L)));
        when(queries.updateProposalPayload(
                1L, 7L, proposalId, revisedPayload, 2L)).thenReturn(1);

        MailDtos.ActionProposal result = service.updateProposal(
                1L, 7L, proposalId, "corr-proposal-edit",
                new MailDtos.ProposalUpdateRequest(revisedPayload, 2L));

        assertThat(result.proposedPayload()).isEqualTo(revisedPayload);
        assertThat(result.version()).isEqualTo(3L);
        verify(commands).audit(
                eq(1L), eq(7L), eq("mail.proposal.updated"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-proposal-edit"), anyMap(), anyMap());
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_ACTION_PROPOSAL"), eq(proposalId),
                eq("mail.proposal.updated"), anyMap(), eq("corr-proposal-edit"));
    }

    @Test
    void acceptedProposalExposesAStableOwnerHandoffAndReturnFocus() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.now();
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(queries.proposalHandoff(1L, 7L, proposalId)).thenReturn(Optional.of(
                new MailQueryRepository.ProposalHandoffRow(
                        proposalId, commandId, "/calendar/schedule?action=create",
                        "ACCEPTED", null, updatedAt, 3L)));

        MailDtos.ProposalHandoff handoff = service.proposalHandoff(1L, 7L, proposalId);

        assertThat(handoff.commandId()).isEqualTo(commandId);
        assertThat(handoff.ownerRoute()).isEqualTo("/calendar/schedule?action=create");
        assertThat(handoff.returnTo()).isEqualTo("/mail/actions?proposalId=" + proposalId);
        assertThat(handoff.focus()).isEqualTo("mail-proposal-" + proposalId);
        assertThat(handoff.status()).isEqualTo(MailDtos.ProposalHandoffStatus.ACCEPTED);
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

    private MailDtos.ActionProposal escalationProposal(
            UUID proposalId,
            UUID threadId,
            ProposalStatus status,
            long version) {
        return new MailDtos.ActionProposal(
                proposalId, threadId, ProposalType.ESCALATE_NOTIFICATION,
                1, status, "알림 에스컬레이션", "검토된 조치를 알립니다.",
                List.of(Map.of("messageHash", "a".repeat(64))),
                Map.of(
                        "channel", "IN_APP",
                        "urgency", "HIGH",
                        "requiresConfirmation", true),
                new BigDecimal("0.9200"), "LOW",
                "APP.MAIL", "UPDATE", "/mail/actions",
                OffsetDateTime.now().plusDays(1), version);
    }

    private MailDtos.TenantPolicy policy() {
        return new MailDtos.TenantPolicy(
                true, true, true, true, true, false, 365, 25, 0L);
    }

    private MailDtos.AccountSummary account() {
        return new MailDtos.AccountSummary(
                UUID.randomUUID(), "member@sk.com", "구성원", "PERSONAL",
                ProviderType.DWP_SANDBOX, "ACTIVE", "READY", true);
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
