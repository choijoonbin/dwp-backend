package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.BodyFormat.TEXT;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.RecipientType.TO;
import static com.dwp.services.platform.mail.MailTypes.DeliveryMode.SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class MailWorkspaceServiceTest {

    @Mock
    private MailWorkspaceRepository repository;
    @Mock
    private MailQueryRepository queries;
    @Mock
    private MailService mail;
    @Mock
    private TenantMediaStorage storage;
    @Mock
    private MailAttachmentScanner attachmentScanner;

    private MailWorkspaceService service;

    @BeforeEach
    void setUp() {
        lenient().when(repository.composeProviderContext(
                        anyLong(), anyLong(), any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(
                        new MailWorkspaceRepository.ComposeProviderContext(
                                invocation.getArgument(2),
                                MailTypes.ProviderType.DWP_SANDBOX,
                                UUID.randomUUID(), null, "example.com", "sandbox-account")));
        lenient().when(repository.composeSenderMode(
                        anyLong(), anyLong(), any(UUID.class)))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        service = new MailWorkspaceService(
                repository, queries, mail, storage, List.of(attachmentScanner));
    }

    @Test
    void exactAdvancedComposeReplayReturnsOriginalAfterAttachmentsWereBound() {
        UUID accountId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID signatureId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        MailDtos.ThreadDetail thread = mock(MailDtos.ThreadDetail.class);
        MailWorkspaceDtos.DeliveryReceipt receipt = mock(MailWorkspaceDtos.DeliveryReceipt.class);
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, List.of(attachmentId), idempotencyKey,
                "Body", templateId, signatureId);
        when(repository.advancedComposeCommand(1L, 7L, idempotencyKey))
                .thenReturn(Optional.of(new MailWorkspaceRepository.AdvancedComposeCommand(
                        threadId, deliveryId, accountId, fingerprint(7L, request))));
        when(mail.thread(1L, 7L, threadId)).thenReturn(thread);
        when(repository.delivery(1L, 7L, deliveryId)).thenReturn(Optional.of(receipt));

        MailWorkspaceService serviceWithoutScanner =
                new MailWorkspaceService(repository, queries, mail, storage);
        MailWorkspaceDtos.AdvancedComposeResult result = serviceWithoutScanner.compose(
                1L, 7L, "corr-replay", request);

        assertThat(result.thread()).isSameAs(thread);
        assertThat(result.receipt()).isSameAs(receipt);
        verify(repository, never()).attachmentsReady(1L, 7L, List.of(attachmentId));
        verify(repository, never()).templateForSend(1L, 7L, accountId, templateId);
        verify(repository, never()).signatureForSend(1L, 7L, accountId, signatureId);
    }

    @Test
    void changedAdvancedComposeReplayStillConflictsBeforeAttachmentValidation() {
        UUID accountId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, attachmentId, idempotencyKey);
        when(repository.advancedComposeCommand(1L, 7L, idempotencyKey))
                .thenReturn(Optional.of(new MailWorkspaceRepository.AdvancedComposeCommand(
                        UUID.randomUUID(), UUID.randomUUID(), accountId, "f".repeat(64))));

        assertThatThrownBy(() -> service.compose(1L, 7L, "corr-conflict", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different message");
        verify(repository, never()).attachmentsReady(1L, 7L, List.of(attachmentId));
    }

    @Test
    void composeContextDisablesAttachmentsWithoutTrustedScanner() {
        UUID accountId = UUID.randomUUID();
        when(queries.accounts(1L, 7L)).thenReturn(List.of(new MailDtos.AccountSummary(
                accountId, "sender@example.com", "Sender", "PERSONAL",
                MailTypes.ProviderType.DWP_SANDBOX, "ACTIVE", "CURRENT", true)));
        when(repository.preferences(1L, 7L)).thenReturn(preferences());
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(repository.templates(1L, 7L, false)).thenReturn(List.of());
        when(repository.signatures(1L, 7L, false)).thenReturn(List.of());
        MailWorkspaceService serviceWithoutScanner =
                new MailWorkspaceService(repository, queries, mail, storage);

        assertThat(serviceWithoutScanner.composeContext(1L, 7L).capabilities().attachments())
                .isFalse();
    }

    @Test
    void composeContextProjectsCapabilitiesForEachSelectedAccountProvider() {
        UUID sandboxAccountId = UUID.randomUUID();
        UUID undeployedGraphAccountId = UUID.randomUUID();
        when(queries.accounts(1L, 7L)).thenReturn(List.of(
                new MailDtos.AccountSummary(
                        sandboxAccountId, "sender@example.com", "Sender", "PERSONAL",
                        MailTypes.ProviderType.DWP_SANDBOX, "ACTIVE", "CURRENT", true),
                new MailDtos.AccountSummary(
                        undeployedGraphAccountId, "shared@example.com", "Shared", "SHARED",
                        MailTypes.ProviderType.MICROSOFT_GRAPH, "ACTIVE", "CURRENT", false)));
        when(repository.preferences(1L, 7L)).thenReturn(preferences());
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(repository.templates(1L, 7L, false)).thenReturn(List.of());
        when(repository.signatures(1L, 7L, false)).thenReturn(List.of());
        when(repository.composeProviderContext(1L, 7L, undeployedGraphAccountId))
                .thenReturn(Optional.of(new MailWorkspaceRepository.ComposeProviderContext(
                        undeployedGraphAccountId, MailTypes.ProviderType.MICROSOFT_GRAPH,
                        UUID.randomUUID(), null, "example.com", "graph-account")));

        MailWorkspaceDtos.ComposeContext context = service.composeContext(1L, 7L);

        assertThat(context.capabilities())
                .isEqualTo(context.accountCapabilities().get(sandboxAccountId));
        assertThat(context.accountCapabilities().get(sandboxAccountId)).satisfies(capabilities -> {
            assertThat(capabilities.html()).isTrue();
            assertThat(capabilities.bcc()).isTrue();
            assertThat(capabilities.attachments()).isTrue();
            assertThat(capabilities.scheduling()).isTrue();
            assertThat(capabilities.maximumAttachmentBytes()).isEqualTo(25L * 1024L * 1024L);
        });
        assertThat(context.accountCapabilities().get(undeployedGraphAccountId)).satisfies(capabilities -> {
            assertThat(capabilities.multipleRecipients()).isFalse();
            assertThat(capabilities.html()).isFalse();
            assertThat(capabilities.bcc()).isFalse();
            assertThat(capabilities.attachments()).isFalse();
            assertThat(capabilities.scheduling()).isFalse();
        });
        assertThat(context.accountReadiness().get(sandboxAccountId)).satisfies(readiness -> {
            assertThat(readiness.state()).isEqualTo("READY");
            assertThat(readiness.source()).isEqualTo("CONNECTOR_RUNTIME");
            assertThat(readiness.observedAt()).isNotNull();
            assertThat(readiness.errorCode()).isNull();
            assertThat(readiness.credentialConfigured()).isTrue();
            assertThat(readiness.action()).isEqualTo("NONE");
            assertThat(readiness.consentEvidence().state()).isEqualTo("NOT_REQUIRED");
            assertThat(readiness.tokenEvidence().state()).isEqualTo("NOT_REQUIRED");
            assertThat(readiness.featureReadiness()).containsOnlyKeys(
                    "SEND", "BCC", "HTML_BODY", "ATTACHMENTS", "SCHEDULING");
            assertThat(readiness.featureReadiness().get("BCC").state()).isEqualTo("READY");
        });
        assertThat(context.accounts()).filteredOn(account ->
                        account.accountId().equals(sandboxAccountId))
                .singleElement()
                .extracting(MailDtos.AccountSummary::readiness)
                .isEqualTo(context.accountReadiness().get(sandboxAccountId));
        assertThat(context.accountReadiness().get(undeployedGraphAccountId)).satisfies(readiness -> {
            assertThat(readiness.state()).isEqualTo("UNAVAILABLE");
            assertThat(readiness.errorCode()).isEqualTo("MAIL_CREDENTIAL_NOT_CONFIGURED");
            assertThat(readiness.credentialConfigured()).isFalse();
            assertThat(readiness.action()).isEqualTo("ACTIVATE_EXTERNALLY");
        });
    }

    @Test
    void externalComposeFeaturesFailClosedWithoutConsentAndTokenEvidence() {
        UUID accountId = UUID.randomUUID();
        MailConnectorPort connector = mock(MailConnectorPort.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.MICROSOFT_GRAPH, "graph", "graph",
                Set.of(MailConnectorPort.Capability.SEND,
                        MailConnectorPort.Capability.BCC,
                        MailConnectorPort.Capability.HTML_BODY,
                        MailConnectorPort.Capability.ATTACHMENTS)));
        when(connector.readiness(any())).thenReturn(new MailConnectorPort.Readiness(
                MailConnectorPort.ReadinessState.READY, Instant.now(), null, null));
        MailWorkspaceService externalService = new MailWorkspaceService(
                repository, queries, mail, storage, List.of(attachmentScanner),
                new MailConnectorRegistry(List.of(connector)));
        when(queries.accounts(1L, 7L)).thenReturn(List.of(new MailDtos.AccountSummary(
                accountId, "sender@example.com", "Sender", "PERSONAL",
                MailTypes.ProviderType.MICROSOFT_GRAPH, "ACTIVE", "CURRENT", true)));
        when(repository.preferences(1L, 7L)).thenReturn(preferences());
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(repository.templates(1L, 7L, false)).thenReturn(List.of());
        when(repository.signatures(1L, 7L, false)).thenReturn(List.of());
        when(repository.composeProviderContext(1L, 7L, accountId)).thenReturn(Optional.of(
                new MailWorkspaceRepository.ComposeProviderContext(
                        accountId, MailTypes.ProviderType.MICROSOFT_GRAPH,
                        UUID.randomUUID(), URI.create("secret://graph-credential"),
                        "example.com", "graph-account")));

        MailWorkspaceDtos.ComposeContext context = externalService.composeContext(1L, 7L);

        assertThat(context.accountCapabilities().get(accountId)).satisfies(capabilities -> {
            assertThat(capabilities.multipleRecipients()).isFalse();
            assertThat(capabilities.bcc()).isFalse();
            assertThat(capabilities.html()).isFalse();
            assertThat(capabilities.attachments()).isFalse();
            assertThat(capabilities.scheduling()).isFalse();
        });
        assertThat(context.accountReadiness().get(accountId)).satisfies(readiness -> {
            assertThat(readiness.state()).isEqualTo("UNAVAILABLE");
            assertThat(readiness.errorCode()).isEqualTo("MAIL_OAUTH_EVIDENCE_UNAVAILABLE");
            assertThat(readiness.consentEvidence().state()).isEqualTo("UNKNOWN");
            assertThat(readiness.tokenEvidence().state()).isEqualTo("UNKNOWN");
            assertThat(readiness.featureReadiness().values())
                    .allMatch(feature -> "UNAVAILABLE".equals(feature.state()));
        });
    }

    @Test
    void composeContextPreservesSafeLastSuccessfulSyncEvidence() {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime lastSync = OffsetDateTime.parse("2026-09-17T01:02:03Z");
        MailDtos.AccountReadiness stored = new MailDtos.AccountReadiness(
                "UNAVAILABLE", "NO_RUNTIME_ATTESTATION",
                OffsetDateTime.parse("2026-09-17T01:03:03Z"),
                "OLD_SAFE_CODE", true, lastSync, "ACCOUNT", "RETRY");
        when(queries.accounts(1L, 7L)).thenReturn(List.of(new MailDtos.AccountSummary(
                accountId, "sender@example.com", "Sender", "PERSONAL",
                MailTypes.ProviderType.DWP_SANDBOX, "ACTIVE", "CURRENT", true, stored)));
        when(repository.preferences(1L, 7L)).thenReturn(preferences());
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(repository.templates(1L, 7L, false)).thenReturn(List.of());
        when(repository.signatures(1L, 7L, false)).thenReturn(List.of());

        MailDtos.AccountReadiness runtime = service.composeContext(1L, 7L)
                .accountReadiness().get(accountId);

        assertThat(runtime.state()).isEqualTo("READY");
        assertThat(runtime.source()).isEqualTo("CONNECTOR_RUNTIME");
        assertThat(runtime.lastSuccessfulSyncAt()).isEqualTo(lastSync);
        assertThat(runtime.lastSuccessfulSyncScope()).isEqualTo("ACCOUNT");
        assertThat(runtime.errorCode()).isNull();
        assertThat(runtime.featureReadiness().values()).allSatisfy(feature -> {
            assertThat(feature.lastSuccessfulAt()).isNull();
            assertThat(feature.lastSuccessfulScope()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void composeFailsBeforePersistenceWhenSelectedProviderDoesNotSupportHtml() {
        UUID accountId = UUID.randomUUID();
        MailConnectorPort connector = mock(MailConnectorPort.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.DWP_SANDBOX, "test", "test",
                Set.of(MailConnectorPort.Capability.SEND)));
        when(connector.readiness(any())).thenReturn(new MailConnectorPort.Readiness(
                MailConnectorPort.ReadinessState.READY, Instant.now(), null, null));
        MailWorkspaceService limitedService = new MailWorkspaceService(
                repository, queries, mail, storage, List.of(attachmentScanner),
                new MailConnectorRegistry(List.of(connector)));
        MailWorkspaceDtos.AdvancedComposeRequest request =
                new MailWorkspaceDtos.AdvancedComposeRequest(
                        accountId,
                        List.of(new MailWorkspaceDtos.Recipient(
                                TO, "Recipient", "recipient@example.com")),
                        "Subject", "<p>Body</p>", MailWorkspaceDtos.BodyFormat.HTML,
                        List.of(), null, null, null, null, UUID.randomUUID());
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));

        assertThatThrownBy(() -> limitedService.compose(
                1L, 7L, "corr-unsupported-html", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("does not support HTML");
        verify(repository, never()).attachmentsReady(1L, 7L, List.of());
    }

    @Test
    void uploadFailsClosedWithoutTrustedScanner() {
        MailWorkspaceService serviceWithoutScanner =
                new MailWorkspaceService(repository, queries, mail, storage);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                "safe content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> serviceWithoutScanner.uploadAttachment(1L, 7L, file))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));
        verifyNoInteractions(storage);
    }

    @Test
    void cleanScannerEvidenceIsPersistedWithReadyAttachment() {
        byte[] content = "safe content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", content);
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(attachmentScanner.scan(any())).thenReturn(new MailAttachmentScanner.ScanResult(
                MailAttachmentScanner.Verdict.CLEAN, "scanner:clean:definition-42"));
        when(storage.store(eq(1L), eq("mail/compose/7"), eq("txt"), any()))
                .thenReturn("1/mail/compose/7/note.txt");

        service.uploadAttachment(1L, 7L, file);

        verify(repository).createAttachment(
                eq(1L), eq(7L), any(), eq("1/mail/compose/7/note.txt"),
                eq("note.txt"), eq("text/plain"), eq((long) content.length),
                eq(sha256(content)), eq("scanner:clean:definition-42"));
    }

    @Test
    void rejectedScannerVerdictNeverStoresAttachment() {
        byte[] content = "unsafe content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", content);
        when(repository.maximumAttachmentMb(1L)).thenReturn(25);
        when(attachmentScanner.scan(any())).thenReturn(new MailAttachmentScanner.ScanResult(
                MailAttachmentScanner.Verdict.REJECTED, "malware:test-signature"));

        assertThatThrownBy(() -> service.uploadAttachment(1L, 7L, file))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("rejected");
        verifyNoInteractions(storage);
        verify(repository, never()).createAttachment(
                anyLong(), anyLong(), any(), any(), any(), any(),
                anyLong(), any(), any());
    }

    @Test
    void newComposeWithAttachmentsFailsWhenScannerWasRemoved() {
        UUID accountId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, attachmentId, UUID.randomUUID());
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        MailWorkspaceService serviceWithoutScanner =
                new MailWorkspaceService(repository, queries, mail, storage);

        assertThatThrownBy(() -> serviceWithoutScanner.compose(
                1L, 7L, "corr-no-scanner", request))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));
        verify(repository, never()).attachmentsReady(1L, 7L, List.of(attachmentId));
    }

    @Test
    void newDraftSendWithAttachmentsFailsWhenScannerWasRemoved() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MailWorkspaceDtos.ComposeOptions options = new MailWorkspaceDtos.ComposeOptions(
                accountId,
                List.of(new MailWorkspaceDtos.Recipient(
                        TO, "Recipient", "recipient@example.com")),
                TEXT,
                List.of(attachmentId),
                OffsetDateTime.now().plusHours(1),
                "Asia/Seoul",
                null,
                null);
        MailDtos.DraftUpdateRequest request = new MailDtos.DraftUpdateRequest(
                "recipient@example.com", "Recipient", "Subject", "Body",
                SEND, UUID.randomUUID(), 2L, options);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        MailWorkspaceService serviceWithoutScanner =
                new MailWorkspaceService(repository, queries, mail, storage);

        assertThatThrownBy(() -> serviceWithoutScanner.sendDraft(
                1L, 7L, threadId, "corr-draft-no-scanner", request))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));
        verify(repository, never()).attachmentsReadyForThread(
                1L, 7L, List.of(attachmentId), threadId);
    }

    @Test
    void advancedComposeRejectsMissingSelectedTemplateMandatoryContent() {
        UUID accountId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, List.of(), UUID.randomUUID(), "Ordinary body", templateId, null);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        when(repository.templateForSend(1L, 7L, accountId, templateId))
                .thenReturn(Optional.of(template(
                        templateId, MailWorkspaceDtos.AssetScope.ORGANIZATION,
                        null, "Required legal notice")));

        assertThatThrownBy(() -> service.compose(1L, 7L, "corr-template", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("template mandatory content");
        verify(repository, never()).attachmentsReady(1L, 7L, List.of());
    }

    @Test
    void advancedComposeEnforcesOrganizationSignatureSelectedByDefaultPreference() {
        UUID accountId = UUID.randomUUID();
        UUID signatureId = UUID.randomUUID();
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, List.of(), UUID.randomUUID(), "Ordinary body", null, null);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        when(repository.preferredSignatureId(1L, 7L)).thenReturn(Optional.of(signatureId));
        when(repository.signatureForSend(1L, 7L, accountId, signatureId))
                .thenReturn(Optional.of(signature(
                        signatureId, MailWorkspaceDtos.AssetScope.ORGANIZATION,
                        null, "Required company footer")));

        assertThatThrownBy(() -> service.compose(1L, 7L, "corr-default-signature", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("signature mandatory content");
    }

    @Test
    void draftSendRejectsMissingSelectedSignatureMandatoryContent() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID signatureId = UUID.randomUUID();
        MailWorkspaceDtos.ComposeOptions options = new MailWorkspaceDtos.ComposeOptions(
                accountId,
                List.of(new MailWorkspaceDtos.Recipient(
                        TO, "Recipient", "recipient@example.com")),
                TEXT,
                List.of(),
                OffsetDateTime.now().plusHours(1),
                "Asia/Seoul",
                null,
                signatureId);
        MailDtos.DraftUpdateRequest request = new MailDtos.DraftUpdateRequest(
                "recipient@example.com", "Recipient", "Subject", "Ordinary body",
                SEND, UUID.randomUUID(), 2L, options);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        when(repository.signatureForSend(1L, 7L, accountId, signatureId))
                .thenReturn(Optional.of(signature(
                        signatureId, MailWorkspaceDtos.AssetScope.PERSONAL,
                        null, "Required personal footer")));

        assertThatThrownBy(() -> service.sendDraft(
                1L, 7L, threadId, "corr-draft-signature", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("signature mandatory content");
        verify(repository, never()).attachmentsReadyForThread(1L, 7L, List.of(), threadId);
    }

    @Test
    void archivedOrOutOfScopeSelectedAssetFailsClosed() {
        UUID accountId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, List.of(), UUID.randomUUID(), "Body", templateId, null);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        when(repository.templateForSend(1L, 7L, accountId, templateId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compose(1L, 7L, "corr-stale-template", request))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error).hasMessageContaining("template was not found");
                });
    }

    @Test
    void advancedComposeRejectsCombinedAttachmentsOverTenantLimit() {
        UUID accountId = UUID.randomUUID();
        List<UUID> attachmentIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        MailWorkspaceDtos.AdvancedComposeRequest request = request(
                accountId, attachmentIds, UUID.randomUUID(), "Body", null, null);
        when(repository.composeAccount(1L, 7L, accountId)).thenReturn(Optional.of(accountId));
        when(repository.attachmentsReady(1L, 7L, attachmentIds)).thenReturn(true);
        when(repository.maximumAttachmentMb(1L)).thenReturn(1);
        when(repository.attachmentsWithinTotalSize(
                1L, 7L, attachmentIds, null, 1024L * 1024L)).thenReturn(false);

        assertThatThrownBy(() -> service.compose(1L, 7L, "corr-attachment-total", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("combined attachment size");
    }

    @Test
    void missingOrInvisibleAttachmentDoesNotLoadStorage() {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(repository.visibleAttachment(1L, 7L, threadId, messageId, attachmentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadAttachment(
                1L, 7L, threadId, messageId, attachmentId))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(storage);
    }

    @Test
    void visibleAttachmentLoadsTheExactTenantStorageReference() {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String storageReference = "1/mail/compose/7/quarterly-report.pdf";
        byte[] content = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(content);
        when(repository.visibleAttachment(1L, 7L, threadId, messageId, attachmentId))
                .thenReturn(Optional.of(
                        new MailWorkspaceRepository.AttachmentContentReference(
                                storageReference, "quarterly report.pdf", "application/pdf",
                                content.length, "a".repeat(64))));
        when(storage.load(1L, storageReference)).thenReturn(resource);

        assertThat(service.downloadAttachment(1L, 7L, threadId, messageId, attachmentId))
                .satisfies(download -> {
                    assertThat(download.resource()).isSameAs(resource);
                    assertThat(download.fileName()).isEqualTo("quarterly report.pdf");
                    assertThat(download.contentType()).isEqualTo("application/pdf");
                    assertThat(download.sizeBytes()).isEqualTo(content.length);
                    assertThat(download.checksumSha256()).isEqualTo("a".repeat(64));
                });
        verify(storage).load(1L, storageReference);
    }

    @Test
    void replyRequiresDefaultSignatureMandatoryContent() {
        UUID threadId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID signatureId = UUID.randomUUID();
        when(repository.replyAccount(1L, 7L, threadId)).thenReturn(Optional.of(accountId));
        when(repository.defaultSignatureForReply(1L, 7L, accountId)).thenReturn(Optional.of(
                signature(signatureId, MailWorkspaceDtos.AssetScope.ORGANIZATION,
                        null, "Company confidential notice")));

        assertThatThrownBy(() -> service.validateReplyBody(
                1L, 7L, threadId, "Thanks, I will review it."))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("mandatory content");

        service.validateReplyBody(
                1L, 7L, threadId,
                "Thanks, I will review it.\n\nCompany   confidential notice");
    }

    @Test
    void replyFailsClosedWhenItsSendingAccountIsNoLongerAvailable() {
        UUID threadId = UUID.randomUUID();
        when(repository.replyAccount(1L, 7L, threadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateReplyBody(
                1L, 7L, threadId, "Reply"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("no longer available");
    }

    private MailWorkspaceDtos.AdvancedComposeRequest request(
            UUID accountId, UUID attachmentId, UUID idempotencyKey) {
        return request(accountId, List.of(attachmentId), idempotencyKey,
                "Body", null, null);
    }

    private MailWorkspaceDtos.AdvancedComposeRequest request(
            UUID accountId,
            List<UUID> attachmentIds,
            UUID idempotencyKey,
            String body,
            UUID templateId,
            UUID signatureId) {
        return new MailWorkspaceDtos.AdvancedComposeRequest(
                accountId,
                List.of(new MailWorkspaceDtos.Recipient(
                        TO, "Recipient", "recipient@example.com")),
                "Subject",
                body,
                TEXT,
                attachmentIds,
                OffsetDateTime.now().plusHours(1),
                "Asia/Seoul",
                templateId,
                signatureId,
                idempotencyKey);
    }

    private MailWorkspaceDtos.Template template(
            UUID templateId,
            MailWorkspaceDtos.AssetScope scope,
            UUID accountId,
            String mandatoryContent) {
        return new MailWorkspaceDtos.Template(
                templateId, "Template", "Subject", "Template body", TEXT,
                scope, accountId, false, mandatoryContent, "PUBLISHED", 1, true, 3L,
                OffsetDateTime.now());
    }

    private MailWorkspaceDtos.Signature signature(
            UUID signatureId,
            MailWorkspaceDtos.AssetScope scope,
            UUID accountId,
            String mandatoryContent) {
        return new MailWorkspaceDtos.Signature(
                signatureId, "Signature", "Signature body", TEXT,
                scope, accountId, true, false, false, mandatoryContent,
                "PUBLISHED", 1, true, 3L, OffsetDateTime.now());
    }

    private MailWorkspaceDtos.Preferences preferences() {
        return new MailWorkspaceDtos.Preferences(
                "COMFORTABLE", "ASK", 0, false,
                true, true, true, null, null, Map.of(), 0L);
    }

    private String fingerprint(
            long userId, MailWorkspaceDtos.AdvancedComposeRequest request) {
        String recipients = "|TO:recipient@example.com:Recipient";
        String value = String.join("\u001f",
                String.valueOf(userId), request.accountId().toString(), recipients,
                request.subject().trim(), request.body().trim(), request.bodyFormat().name(),
                request.attachmentIds().toString(), String.valueOf(request.scheduleAt()),
                request.timeZone(), String.valueOf(request.templateId()),
                String.valueOf(request.signatureId()), request.classification().name(),
                String.valueOf(request.externalRecipientConfirmed()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
