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
import static org.mockito.Mockito.never;
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
    @Mock
    private MailDeliveryCompletionService deliveryCompletion;
    @Mock
    private MailNotificationEvents notificationEvents;
    @Mock
    private MailWorkspaceRepository workspaceRepository;
    @Mock
    private MailAdminMutationReceipts adminReceipts;

    private MailService service;
    private final MailSendCommandFingerprint sendFingerprints =
            new MailSendCommandFingerprint();

    @BeforeEach
    void setUp() {
        service = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents);
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

    @Test
    void ownerOutcomeProjectsExecutionEvidenceAndPublishesAudit() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        MailQueryRepository.ProposalHandoffRow before =
                new MailQueryRepository.ProposalHandoffRow(
                        proposalId, commandId, "/calendar/schedule?action=create",
                        "ACCEPTED", null, OffsetDateTime.now(), 3L);
        MailQueryRepository.ProposalHandoffRow after =
                new MailQueryRepository.ProposalHandoffRow(
                        proposalId, commandId, "/calendar/schedule?action=create",
                        "EXECUTED", "calendar:event:42", OffsetDateTime.now(), 4L);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(queries.proposalHandoff(1L, 7L, proposalId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(commands.updateProposalOutcome(
                1L, 7L, proposalId, commandId, "EXECUTED", "calendar:event:42", 3L))
                .thenReturn(1);

        MailDtos.ProposalHandoff result = service.recordProposalOutcome(
                1L, 7L, proposalId, "corr-owner",
                new MailDtos.ProposalOutcomeRequest(
                        commandId, MailDtos.ProposalHandoffStatus.EXECUTED,
                        "calendar:event:42", 3L));

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.EXECUTED);
        assertThat(result.resultRef()).isEqualTo("calendar:event:42");
        verify(commands).audit(
                eq(1L), eq(7L), eq("mail.action.owner-outcome"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-owner"), anyMap(), anyMap());
        verify(commands).domainEvent(
                eq(1L), eq("MAIL_ACTION_PROPOSAL"), eq(proposalId),
                eq("mail.action.owner-outcome"), anyMap(), eq("corr-owner"));
    }

    @Test
    void repeatedOwnerOutcomeReturnsTheRecordedResultWithoutAnotherMutation() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        MailQueryRepository.ProposalHandoffRow completed =
                new MailQueryRepository.ProposalHandoffRow(
                        proposalId, commandId, "/calendar/schedule?action=create",
                        "EXECUTED", "calendar:event:42", OffsetDateTime.now(), 4L);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(queries.proposalHandoff(1L, 7L, proposalId)).thenReturn(Optional.of(completed));

        MailDtos.ProposalHandoff result = service.recordProposalOutcome(
                1L, 7L, proposalId, "corr-owner-replay",
                new MailDtos.ProposalOutcomeRequest(
                        commandId, MailDtos.ProposalHandoffStatus.EXECUTED,
                        "calendar:event:42", 3L));

        assertThat(result.version()).isEqualTo(4L);
        verify(commands, never()).updateProposalOutcome(
                eq(1L), eq(7L), eq(proposalId), eq(commandId),
                eq("EXECUTED"), eq("calendar:event:42"), eq(3L));
    }

    @Test
    void externalConnectionCannotActivateWithoutVaultedCredentialReference() {
        UUID connectionId = UUID.randomUUID();
        MailService adminService = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                null, null, adminReceipts);
        when(queries.connection(1L, connectionId)).thenReturn(Optional.of(
                new MailDtos.ConnectionSummary(
                        connectionId, "microsoft-graph", "Microsoft 365",
                        ProviderType.MICROSOFT_GRAPH, "OAUTH2", "sk.com",
                        ConnectionState.CONFIGURATION_REQUIRED,
                        List.of("READ", "SEND"), false,
                        null, null, 0L)));

        assertThatThrownBy(() -> adminService.updateConnection(
                1L, 10L, connectionId, "corr-connection",
                UUID.randomUUID(),
                new MailDtos.ConnectionUpdateRequest(
                        "Microsoft 365", "sk.com", null,
                        ConnectionState.ACTIVE, 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("secret-store reference");
    }

    @Test
    void externalConnectionCannotActivateBeforeItsRuntimeAdapterIsDeployed() {
        UUID connectionId = UUID.randomUUID();
        MailService adminService = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                null, null, adminReceipts);
        when(queries.connection(1L, connectionId)).thenReturn(Optional.of(
                new MailDtos.ConnectionSummary(
                        connectionId, "microsoft-graph", "Microsoft 365",
                        ProviderType.MICROSOFT_GRAPH, "OAUTH2", "sk.com",
                        ConnectionState.CONFIGURATION_REQUIRED,
                        List.of("READ", "SEND"), true,
                        null, null, 0L)));
        when(providerCatalog.isRuntimeAvailable(ProviderType.MICROSOFT_GRAPH))
                .thenReturn(false);

        assertThatThrownBy(() -> adminService.updateConnection(
                1L, 10L, connectionId, "corr-runtime",
                UUID.randomUUID(),
                new MailDtos.ConnectionUpdateRequest(
                        "Microsoft 365", "sk.com", null,
                        ConnectionState.ACTIVE, 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("runtime adapter");
    }

    @Test
    void legacyAdminPolicyExactReplayReturnsCurrentStateWithoutMutatingAgain() {
        UUID idempotencyKey = UUID.randomUUID();
        UUID policyId = MailAdminMutationReceipts.tenantPolicyId(1L);
        MailDtos.TenantPolicy current = policy();
        MailDtos.TenantPolicyRequest request = new MailDtos.TenantPolicyRequest(
                true, true, true, true, true, 365, 25, 0L);
        MailService adminService = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                null, null, adminReceipts);
        when(adminReceipts.claimOrReplay(
                1L, 7L, "POLICY_UPDATE", idempotencyKey, null, "corr-policy-replay"))
                .thenReturn(new MailAdminMutationReceipts.Receipt(
                        "POLICY_UPDATE", "a".repeat(64),
                        "MAIL_TENANT_POLICY", policyId, OffsetDateTime.now()));
        when(queries.policy(1L)).thenReturn(current);

        MailDtos.TenantPolicy replay = adminService.updatePolicy(
                1L, 7L, "corr-policy-replay", idempotencyKey, request);

        assertThat(replay).isEqualTo(current);
        verify(commands, never()).updatePolicy(1L, 7L, request);
        verify(adminReceipts, never()).complete(
                1L, 7L, idempotencyKey, "MAIL_TENANT_POLICY", policyId);
    }

    @Test
    void repeatedComposeReturnsTheOriginalThreadWithoutDuplicateDeliveryOrEvidence() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest request = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "중복 방지", "동일 요청입니다.",
                DeliveryMode.SEND, idempotencyKey);
        MailDtos.ThreadSummary existing = thread(threadId, false, 0L);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        String fingerprint = sendFingerprints.compose(7L, request);
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(threadId, 7L, fingerprint));
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(existing));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.compose(
                1L, 7L, "corr-replay", request);

        assertThat(result.thread().threadId()).isEqualTo(threadId);
        verify(commands, never()).enqueueDelivery(
                eq(1L), eq(7L), eq(threadId), eq(idempotencyKey), eq("corr-replay"),
                eq(fingerprint));
        verify(commands, never()).audit(
                eq(1L), eq(7L), eq("mail.message.queued"),
                eq("MAIL_THREAD"), eq(threadId.toString()),
                eq("corr-replay"), anyMap(), anyMap());
        verify(commands, never()).compose(1L, 7L, request, fingerprint);
    }

    @Test
    void repeatedComposeDraftReturnsTheOriginalDraftWithoutAnotherMutation() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest request = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "임시 저장", "동일 초안입니다.",
                DeliveryMode.DRAFT, idempotencyKey);
        String fingerprint = sendFingerprints.compose(7L, request);
        MailDtos.ThreadSummary existing = thread(threadId, false, 0L);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(commands.compose(1L, 7L, request, fingerprint)).thenReturn(
                new MailCommandRepository.ComposeResult(threadId, false, fingerprint));
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(existing));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.compose(
                1L, 7L, "corr-draft-replay", request);

        assertThat(result.thread().threadId()).isEqualTo(threadId);
        verify(commands, never()).enqueueDelivery(
                eq(1L), eq(7L), eq(threadId), eq(idempotencyKey),
                eq("corr-draft-replay"), eq(fingerprint));
        verify(commands, never()).audit(
                eq(1L), eq(7L), eq("mail.draft.saved"),
                eq("MAIL_THREAD"), eq(threadId.toString()),
                eq("corr-draft-replay"), anyMap(), anyMap());
    }

    @Test
    void composeSendIdempotencyKeyRejectsChangedPayload() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest original = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "원본 제목", "원본 본문",
                DeliveryMode.SEND, idempotencyKey);
        MailDtos.ComposeRequest changed = new MailDtos.ComposeRequest(
                "other@sk.com", "다른 수신자", "변경 제목", "변경 본문",
                DeliveryMode.SEND, idempotencyKey);
        String changedFingerprint = sendFingerprints.compose(7L, changed);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.compose(7L, original)));

        assertThatThrownBy(() -> service.compose(
                1L, 7L, "corr-compose-send-drift", changed))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).compose(1L, 7L, changed, changedFingerprint);
        verify(commands, never()).enqueueDelivery(
                eq(1L), eq(7L), eq(threadId), eq(idempotencyKey),
                eq("corr-compose-send-drift"), eq(changedFingerprint));
    }

    @Test
    void legacyComposeDeliveryWithoutFingerprintFailsClosed() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest request = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "기존 명령", "기존 본문",
                DeliveryMode.SEND, idempotencyKey);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(threadId, 7L, null));

        assertThatThrownBy(() -> service.compose(
                1L, 7L, "corr-compose-legacy", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).compose(
                1L, 7L, request, sendFingerprints.compose(7L, request));
    }

    @Test
    void legacyComposeDraftWithoutFingerprintFailsClosed() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest request = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "기존 초안", "기존 초안 본문",
                DeliveryMode.DRAFT, idempotencyKey);
        String fingerprint = sendFingerprints.compose(7L, request);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(commands.compose(1L, 7L, request, fingerprint)).thenReturn(
                new MailCommandRepository.ComposeResult(threadId, false, null));

        assertThatThrownBy(() -> service.compose(
                1L, 7L, "corr-draft-legacy", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).enqueueDelivery(
                eq(1L), eq(7L), eq(threadId), eq(idempotencyKey),
                eq("corr-draft-legacy"), eq(fingerprint));
    }

    @Test
    void composeIdempotencyKeyRejectsChangedPayloadOrMode() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest original = new MailDtos.ComposeRequest(
                "recipient@sk.com", "수신자", "원본 제목", "원본 본문",
                DeliveryMode.DRAFT, idempotencyKey);
        MailDtos.ComposeRequest changed = new MailDtos.ComposeRequest(
                "other@sk.com", "다른 수신자", "변경 제목", "변경 본문",
                DeliveryMode.SEND, idempotencyKey);
        String changedFingerprint = sendFingerprints.compose(7L, changed);
        when(queries.accounts(1L, 7L)).thenReturn(List.of(account()));
        when(commands.composeCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.ComposeResult(
                        threadId, false, sendFingerprints.compose(7L, original)));

        assertThatThrownBy(() -> service.compose(
                1L, 7L, "corr-compose-drift", changed))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).enqueueDelivery(
                eq(1L), eq(7L), eq(threadId), eq(idempotencyKey),
                eq("corr-compose-drift"), eq(changedFingerprint));
    }

    @Test
    void repeatedReplyReturnsTheOriginalThreadWithoutAnotherMessage() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest request =
                new MailDtos.ReplyRequest("재전송된 요청", idempotencyKey);
        MailDtos.ThreadSummary existing = thread(threadId, false, 1L);
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(existing));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.reply(7L, threadId, request)));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.reply(
                1L, 7L, threadId, "corr-reply-replay", request);

        assertThat(result.thread().threadId()).isEqualTo(threadId);
        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(threadId), eq("재전송된 요청"), eq(idempotencyKey));
    }

    @Test
    void exactReplyReplayDoesNotReevaluateLaterSignaturePolicy() {
        service = new MailService(
                queries, commands, providerCatalog, deliveryCompletion,
                notificationEvents, workspaceRepository);
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest request =
                new MailDtos.ReplyRequest("Previously accepted body", idempotencyKey);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 1L)));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.reply(7L, threadId, request)));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.reply(
                1L, 7L, threadId, "corr-signature-replay", request);

        assertThat(result.thread().threadId()).isEqualTo(threadId);
        verify(workspaceRepository, never()).replyAccount(1L, 7L, threadId);
        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(threadId), eq(request.body()), eq(idempotencyKey));
    }

    @Test
    void newReplyFailsClosedWhenDefaultSignatureMandatoryContentIsMissing() {
        service = new MailService(
                queries, commands, providerCatalog, deliveryCompletion,
                notificationEvents, workspaceRepository);
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 1L)));
        when(workspaceRepository.replyAccount(1L, 7L, threadId))
                .thenReturn(Optional.of(accountId));
        when(workspaceRepository.defaultSignatureForReply(1L, 7L, accountId))
                .thenReturn(Optional.of(new MailWorkspaceDtos.Signature(
                        UUID.randomUUID(), "Organization signature", "Signature body",
                        MailWorkspaceDtos.BodyFormat.HTML,
                        MailWorkspaceDtos.AssetScope.ORGANIZATION, null,
                        false, true, false,
                        "<p>Required legal notice</p>", "PUBLISHED", 1, true, 3L,
                        OffsetDateTime.now())));

        assertThatThrownBy(() -> service.reply(
                1L, 7L, threadId, "corr-signature-required",
                new MailDtos.ReplyRequest("Ordinary reply", idempotencyKey)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("mandatory content");

        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(threadId), eq("Ordinary reply"), eq(idempotencyKey));
    }

    @Test
    void replyAllPersistsTheReviewedExplicitRecipients() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        List<MailWorkspaceDtos.Recipient> suppliedRecipients = List.of(
                new MailWorkspaceDtos.Recipient(
                        MailWorkspaceDtos.RecipientType.TO,
                        " Customer ", "Customer@Example.com"),
                new MailWorkspaceDtos.Recipient(
                        MailWorkspaceDtos.RecipientType.CC,
                        "Project", "project@example.com"));
        List<MailWorkspaceDtos.Recipient> persistedRecipients = List.of(
                new MailWorkspaceDtos.Recipient(
                        MailWorkspaceDtos.RecipientType.TO,
                        "Customer", "customer@example.com"),
                new MailWorkspaceDtos.Recipient(
                        MailWorkspaceDtos.RecipientType.CC,
                        "Project", "project@example.com"));
        MailDtos.ReplyRequest request = new MailDtos.ReplyRequest(
                "Reviewed recipients", idempotencyKey, "REPLY_ALL", suppliedRecipients);
        String fingerprint = sendFingerprints.reply(7L, threadId, request);
        MailDtos.ThreadSummary before = thread(threadId, false, 1L);
        MailDtos.ThreadSummary after = thread(threadId, false, 2L);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(commands.insertReply(
                1L, 7L, threadId, request.body(), idempotencyKey, persistedRecipients))
                .thenReturn(true);
        when(commands.enqueueDelivery(
                1L, 7L, threadId, idempotencyKey,
                "corr-reply-all", fingerprint))
                .thenReturn(new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, fingerprint));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.reply(
                1L, 7L, threadId, "corr-reply-all", request);

        assertThat(result.thread().version()).isEqualTo(2L);
        verify(commands).insertReply(
                1L, 7L, threadId, request.body(), idempotencyKey, persistedRecipients);
        verify(commands).audit(
                eq(1L), eq(7L), eq("mail.reply.sent"), eq("MAIL_THREAD"),
                eq(threadId.toString()), eq("corr-reply-all"), anyMap(), anyMap());
    }

    @Test
    void replyIdempotencyKeyRejectsChangedBody() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest original =
                new MailDtos.ReplyRequest("원본 본문", idempotencyKey);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 1L)));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.reply(7L, threadId, original)));

        assertThatThrownBy(() -> service.reply(
                1L, 7L, threadId, "corr-reply-drift",
                new MailDtos.ReplyRequest("변경된 본문", idempotencyKey)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(threadId), eq("변경된 본문"), eq(idempotencyKey));
    }

    @Test
    void replyIdempotencyKeyRejectsAnotherThread() {
        UUID originalThreadId = UUID.randomUUID();
        UUID replayThreadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest request =
                new MailDtos.ReplyRequest("동일한 본문", idempotencyKey);
        when(queries.thread(1L, 7L, replayThreadId))
                .thenReturn(Optional.of(thread(replayThreadId, false, 1L)));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        originalThreadId, 7L,
                        sendFingerprints.reply(7L, originalThreadId, request)));

        assertThatThrownBy(() -> service.reply(
                1L, 7L, replayThreadId, "corr-reply-thread-drift", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(replayThreadId), eq("동일한 본문"), eq(idempotencyKey));
    }

    @Test
    void sharedReplyCannotReplayAnotherActorsCommand() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest request =
                new MailDtos.ReplyRequest("동일한 공유 답장", idempotencyKey);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 1L)));
        when(commands.deliveryCommandForThread(
                1L, 7L, threadId, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 8L,
                        sendFingerprints.reply(8L, threadId, request)));

        assertThatThrownBy(() -> service.reply(
                1L, 7L, threadId, "corr-cross-actor", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).insertReply(
                eq(1L), eq(7L), eq(threadId), eq("동일한 공유 답장"), eq(idempotencyKey));
    }

    @Test
    void duplicateEnqueueOnAnotherThreadMapsToConflict() {
        UUID requestedThreadId = UUID.randomUUID();
        UUID existingThreadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ReplyRequest request =
                new MailDtos.ReplyRequest("새 답장", idempotencyKey);
        String fingerprint = sendFingerprints.reply(7L, requestedThreadId, request);
        when(queries.thread(1L, 7L, requestedThreadId))
                .thenReturn(Optional.of(thread(requestedThreadId, false, 1L)));
        when(commands.insertReply(
                1L, 7L, requestedThreadId, request.body(), idempotencyKey))
                .thenReturn(true);
        when(commands.enqueueDelivery(
                1L, 7L, requestedThreadId, idempotencyKey,
                "corr-enqueue-race", fingerprint)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        existingThreadId, 7L, fingerprint));

        assertThatThrownBy(() -> service.reply(
                1L, 7L, requestedThreadId, "corr-enqueue-race", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).audit(
                eq(1L), eq(7L), eq("mail.reply.sent"), eq("MAIL_THREAD"),
                eq(requestedThreadId.toString()), eq("corr-enqueue-race"),
                anyMap(), anyMap());
    }

    @Test
    void repeatedDraftSendReturnsTheSentThreadWithoutAnotherMutation() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ThreadSummary existing = thread(threadId, false, 2L);
        MailDtos.DraftUpdateRequest request = new MailDtos.DraftUpdateRequest(
                "recipient@sk.com", "수신자", "전송 완료", "이미 전송했습니다.",
                DeliveryMode.SEND, idempotencyKey, 1L);
        when(queries.thread(1L, 7L, threadId)).thenReturn(Optional.of(existing));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.draftSend(7L, threadId, request)));
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.updateDraft(
                1L, 7L, threadId, "corr-draft-replay", request);

        assertThat(result.thread().threadId()).isEqualTo(threadId);
        verify(commands, never()).updateDraft(1L, 7L, threadId, request);
    }

    @Test
    void draftSendIdempotencyKeyRejectsChangedPayloadOrVersion() {
        UUID threadId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.DraftUpdateRequest original = new MailDtos.DraftUpdateRequest(
                "recipient@sk.com", "수신자", "전송 완료", "원본 본문",
                DeliveryMode.SEND, idempotencyKey, 1L);
        MailDtos.DraftUpdateRequest changed = new MailDtos.DraftUpdateRequest(
                "other@sk.com", "다른 수신자", "변경된 제목", "변경된 본문",
                DeliveryMode.SEND, idempotencyKey, 2L);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(thread(threadId, false, 2L)));
        when(commands.deliveryCommand(1L, 7L, idempotencyKey)).thenReturn(
                new MailCommandRepository.DeliveryCommand(
                        threadId, 7L, sendFingerprints.draftSend(7L, threadId, original)));

        assertThatThrownBy(() -> service.updateDraft(
                1L, 7L, threadId, "corr-draft-drift", changed))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different mail send command");

        verify(commands, never()).updateDraft(1L, 7L, threadId, changed);
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
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
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
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.ASSIGN)).thenReturn(true);
        when(queries.isActiveSharedInboxMember(1L, sharedInboxId, 99L)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(
                1L, 7L, threadId, "corr-assign",
                new MailDtos.AssignRequest(99L, "비구성원", 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not an active member");
    }

    @Test
    void sharedThreadDetailProjectsOnlyTheCurrentActorsEffectiveActions() {
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
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());
        when(queries.sharedInboxMembers(1L, sharedInboxId)).thenReturn(List.of());
        when(queries.sharedInboxReplyIdentity(1L, 7L, threadId)).thenReturn(Optional.of(
                new MailDtos.SharedInboxReplyIdentity(
                        "People Help", "people-help@example.com",
                        MailDtos.SharedInboxReplySenderMode.SEND_AS)));
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.ASSIGN)).thenReturn(true);
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.MANAGE)).thenReturn(false);
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.SEND)).thenReturn(true);

        MailDtos.ThreadDetail detail = service.thread(1L, 7L, threadId);

        assertThat(detail.sharedInboxActions())
                .containsExactly("ASSIGN", "REPLY", "SEND_AS");
    }

    @Test
    void sharedInboxAssignmentPublishesTheAssigneeNotificationIntent() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID sharedInboxId = UUID.randomUUID();
        MailDtos.ThreadSummary before = new MailDtos.ThreadSummary(
                threadId, accountId, "People Help", "INBOX",
                sharedInboxId, "People Help", "문의", "확인 부탁드립니다.",
                List.of(new MailDtos.Participant("구성원", "member@sk.com")),
                OffsetDateTime.now(), true, false, Importance.HIGH,
                TriageLane.PRIORITY, WorkflowState.OPEN, null,
                null, null, false, false, Classification.INTERNAL, 1, 4L);
        MailDtos.ThreadSummary after = new MailDtos.ThreadSummary(
                threadId, accountId, "People Help", "INBOX",
                sharedInboxId, "People Help", "문의", "확인 부탁드립니다.",
                before.participants(), before.latestMessageAt(), true, false, Importance.HIGH,
                TriageLane.ASSIGNED, WorkflowState.OPEN, null,
                99L, "담당자", false, false, Classification.INTERNAL, 1, 5L);
        when(queries.thread(1L, 7L, threadId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.ASSIGN)).thenReturn(true);
        when(queries.isActiveSharedInboxMember(1L, sharedInboxId, 99L)).thenReturn(true);
        when(commands.assign(1L, 7L, threadId, 99L, "담당자", 4L)).thenReturn(1);
        when(queries.messages(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.comments(1L, 7L, threadId)).thenReturn(List.of());
        when(queries.proposals(1L, 7L, threadId, 20)).thenReturn(List.of());

        MailDtos.ThreadDetail result = service.assign(
                1L, 7L, threadId, "corr-assign",
                new MailDtos.AssignRequest(99L, "담당자", 4L));

        assertThat(result.thread().assignedUserId()).isEqualTo(99L);
        verify(notificationEvents).sharedInboxAssigned(
                1L, 7L, threadId, 99L, 5L, "corr-assign");
    }

    @Test
    void readOnlySharedInboxGrantCannotCreateInternalComments() {
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
        when(queries.hasSharedInboxPermission(
                1L, sharedInboxId, 7L,
                MailQueryRepository.SharedInboxPermission.MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> service.comment(
                1L, 7L, "Member", threadId, "corr-comment",
                new MailDtos.CommentRequest("Internal note", List.of())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("does not permit");

        verify(commands, never()).insertComment(
                eq(1L), eq(7L), eq("Member"), eq(threadId),
                eq("Internal note"), eq(List.of()));
    }

    @Test
    void sharedInboxUpdateUsesTenantScopedVersionAndWritesAuditEvidence() {
        UUID sharedInboxId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailService adminService = new MailService(
                queries, commands, providerCatalog, deliveryCompletion, notificationEvents,
                null, null, adminReceipts);
        MailDtos.SharedInboxSummary before = sharedInbox(sharedInboxId, "People Help", 240, 2L);
        MailDtos.SharedInboxSummary after = sharedInbox(sharedInboxId, "People Care", 120, 3L);
        when(queries.sharedInbox(1L, sharedInboxId))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        MailDtos.SharedInboxUpdateRequest request = new MailDtos.SharedInboxUpdateRequest(
                "People Care", "구성원 문의를 함께 처리합니다.", 120, "ACTIVE", 2L);
        when(commands.updateSharedInbox(1L, 7L, sharedInboxId, request)).thenReturn(1);

        MailDtos.SharedInboxSummary result = adminService.updateSharedInbox(
                1L, 7L, sharedInboxId, "corr-shared", idempotencyKey, request);

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

    private MailDtos.AccountSummary account() {
        return new MailDtos.AccountSummary(
                UUID.randomUUID(), "member@sk.com", "구성원", "PERSONAL",
                ProviderType.DWP_SANDBOX, "ACTIVE", "READY", true);
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
