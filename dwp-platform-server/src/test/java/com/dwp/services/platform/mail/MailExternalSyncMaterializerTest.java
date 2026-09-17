package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailExternalSyncMaterializerTest {

    private static final String PAYLOAD_SHA256 =
            "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5";

    private AdminMailCompletionRepository repository;
    private TenantMediaStorage storage;
    private MailAttachmentScanner scanner;
    private MailInboundMessageService inboundMessages;
    private MailExternalSyncMaterializer materializer;
    private AdminMailCompletionRepository.AccountRow expectedAccount;
    private AdminMailCompletionRepository.SyncAccountRow lockedAccount;
    private UUID folderId;

    @BeforeEach
    void setUp() {
        repository = mock(AdminMailCompletionRepository.class);
        storage = mock(TenantMediaStorage.class);
        scanner = mock(MailAttachmentScanner.class);
        inboundMessages = mock(MailInboundMessageService.class);
        materializer = new MailExternalSyncMaterializer(
                repository, storage, List.of(scanner), inboundMessages);
        UUID accountId = UUID.randomUUID();
        expectedAccount = new AdminMailCompletionRepository.AccountRow(
                accountId, "inbox@example.test", "provider-account", "cursor-1");
        lockedAccount = new AdminMailCompletionRepository.SyncAccountRow(
                accountId, "inbox@example.test", 77L, "cursor-1", null);
        folderId = UUID.randomUUID();
        when(repository.lockSyncAccount(7, accountId)).thenReturn(Optional.of(lockedAccount));
        when(repository.synchronizationFolder(anyLong(), any(), any()))
                .thenReturn(Optional.of(folderId));
        when(repository.inboundMessage(anyLong(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.updateAccountSyncCursor(7, accountId, "cursor-1", "cursor-2", 91))
                .thenReturn(1);
        when(repository.materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new AdminMailCompletionRepository.InboundMaterialized(
                        UUID.randomUUID(), UUID.randomUUID(), true));
    }

    @Test
    void materializesHtmlAndStoresOnlyCleanAttachmentBeforeAdvancingCursor() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(scanner.scan(any())).thenReturn(new MailAttachmentScanner.ScanResult(
                MailAttachmentScanner.Verdict.CLEAN, "scanner:definition-42"));
        when(storage.store(anyLong(), any(), any(), any())).thenReturn(
                "7/mail/provider-sync/file.txt");
        MailConnectorPort.ProviderMessage message = providerMessage(List.of(
                new MailConnectorPort.ProviderAttachment(
                        "attachment-1", "content-1", "report.txt", "text/plain",
                        content.length, PAYLOAD_SHA256, content, Map.of())));

        MailExternalSyncMaterializer.SyncResult result = materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(List.of(message), "cursor-2", false, false));

        ArgumentCaptor<AdminMailCompletionRepository.InboundMessageRow> capturedMessage =
                ArgumentCaptor.forClass(AdminMailCompletionRepository.InboundMessageRow.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AdminMailCompletionRepository.InboundAttachmentRow>> capturedAttachments =
                ArgumentCaptor.forClass(List.class);
        verify(repository).materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), capturedMessage.capture(),
                capturedAttachments.capture());
        assertThat(capturedMessage.getValue().bodyFormat()).isEqualTo("HTML");
        assertThat(capturedMessage.getValue().body()).isEqualTo("<p>Provider HTML</p>");
        assertThat(capturedMessage.getValue().attachmentProjection().getFirst())
                .containsEntry("scanState", "READY")
                .containsEntry("providerAttachmentReference", "attachment-1");
        assertThat(capturedAttachments.getValue()).singleElement().satisfies(attachment -> {
            assertThat(attachment.scanState()).isEqualTo("READY");
            assertThat(attachment.storageReference())
                    .isEqualTo("7/mail/provider-sync/file.txt");
        });
        assertThat(result.inserted()).isOne();
        assertThat(result.blockedAttachments()).isZero();
        verify(inboundMessages).inboundMessageMaterialized(
                anyLong(), any(), any(), any());
        verify(repository).updateAccountSyncCursor(
                7, expectedAccount.id(), "cursor-1", "cursor-2", 91);
    }

    @Test
    void unavailableProviderAttachmentIsExplicitlyBlockedAndNeverStoredOrScanned() {
        MailConnectorPort.ProviderMessage message = providerMessage(List.of(
                new MailConnectorPort.ProviderAttachment(
                        "attachment-2", "provider-fetch-token", "remote.pdf",
                        "application/pdf", 4096, null, null, Map.of("inline", "false"))));

        MailExternalSyncMaterializer.SyncResult result = materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(List.of(message), "cursor-2", false, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AdminMailCompletionRepository.InboundAttachmentRow>> attachments =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<AdminMailCompletionRepository.InboundMessageRow> capturedMessage =
                ArgumentCaptor.forClass(AdminMailCompletionRepository.InboundMessageRow.class);
        verify(repository).materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), capturedMessage.capture(),
                attachments.capture());
        assertThat(attachments.getValue()).singleElement().satisfies(attachment -> {
            assertThat(attachment.scanState()).isEqualTo("BLOCKED");
            assertThat(attachment.scanEvidence())
                    .contains("PROVIDER_CONTENT_NOT_FETCHED");
        });
        assertThat(capturedMessage.getValue().attachmentProjection().getFirst())
                .containsEntry("scanState", "BLOCKED")
                .containsEntry("contentReference", "provider-fetch-token");
        assertThat(result.blockedAttachments()).isOne();
        verify(scanner, never()).scan(any());
        verify(storage, never()).store(anyLong(), any(), any(), any());
    }

    @Test
    void duplicateProviderMessageOnlyAdvancesCursorAndDoesNotRestageAttachments() {
        when(repository.inboundMessage(
                7, expectedAccount.id(), "thread-1", "message-1"))
                .thenReturn(Optional.of(new AdminMailCompletionRepository.InboundIdentity(
                        UUID.randomUUID(), UUID.randomUUID())));

        MailExternalSyncMaterializer.SyncResult result = materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(
                        List.of(providerMessage(List.of())), "cursor-2", false, false));

        assertThat(result.inserted()).isZero();
        assertThat(result.duplicate()).isOne();
        verify(repository, never()).materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), any(), any());
        verify(repository).updateAccountSyncCursor(
                7, expectedAccount.id(), "cursor-1", "cursor-2", 91);
        verify(inboundMessages, never()).inboundMessageMaterialized(
                anyLong(), any(), any(), any());
    }

    @Test
    void cursorDriftFailsBeforeAnyMessageMutation() {
        when(repository.lockSyncAccount(7, expectedAccount.id())).thenReturn(Optional.of(
                new AdminMailCompletionRepository.SyncAccountRow(
                        expectedAccount.id(), expectedAccount.email(), 77L,
                        "different-cursor", null)));

        assertThatThrownBy(() -> materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(
                        List.of(providerMessage(List.of())), "cursor-2", false, false)))
                .isInstanceOf(MailExternalSyncMaterializer.SyncFailure.class)
                .hasMessageContaining("SYNC_CURSOR_CONFLICT");
        verify(repository, never()).materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), any(), any());
        verify(repository, never()).updateAccountSyncCursor(
                anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void partialBatchCommitsItsNextCursorForContinuation() {
        MailExternalSyncMaterializer.SyncResult result = materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(List.of(), "cursor-2", false, true));

        assertThat(result.partial()).isTrue();
        assertThat(result.inserted()).isZero();
        verify(repository).updateAccountSyncCursor(
                7, expectedAccount.id(), "cursor-1", "cursor-2", 91);
    }

    @Test
    void storageObjectIsDeletedWhenDatabaseMaterializationFails() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(scanner.scan(any())).thenReturn(new MailAttachmentScanner.ScanResult(
                MailAttachmentScanner.Verdict.CLEAN, "scanner:definition-42"));
        when(storage.store(anyLong(), any(), any(), any())).thenReturn(
                "7/mail/provider-sync/orphan.txt");
        when(repository.materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        MailConnectorPort.ProviderMessage message = providerMessage(List.of(
                new MailConnectorPort.ProviderAttachment(
                        "attachment-rollback", null, "report.txt", "text/plain",
                        content.length, PAYLOAD_SHA256, content, Map.of())));

        assertThatThrownBy(() -> materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(
                        List.of(message), "cursor-2", false, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(storage).delete(7L, "7/mail/provider-sync/orphan.txt");
        verify(repository, never()).updateAccountSyncCursor(
                anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void raceDuplicateDeletesNewlyStoredObjectAndSkipsInboundHook() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(scanner.scan(any())).thenReturn(new MailAttachmentScanner.ScanResult(
                MailAttachmentScanner.Verdict.CLEAN, "scanner:definition-42"));
        when(storage.store(anyLong(), any(), any(), any())).thenReturn(
                "7/mail/provider-sync/race.txt");
        when(repository.materializeInboundMessage(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminMailCompletionRepository.InboundMaterialized(
                        UUID.randomUUID(), UUID.randomUUID(), false));
        MailConnectorPort.ProviderMessage message = providerMessage(List.of(
                new MailConnectorPort.ProviderAttachment(
                        "attachment-race", null, "report.txt", "text/plain",
                        content.length, PAYLOAD_SHA256, content, Map.of())));

        MailExternalSyncMaterializer.SyncResult result = materializer.materialize(
                7, 91, expectedAccount,
                new MailConnectorPort.SyncBatch(
                        List.of(message), "cursor-2", false, false));

        assertThat(result.duplicate()).isOne();
        verify(storage).delete(7L, "7/mail/provider-sync/race.txt");
        verify(inboundMessages, never()).inboundMessageMaterialized(
                anyLong(), any(), any(), any());
    }

    private MailConnectorPort.ProviderMessage providerMessage(
            List<MailConnectorPort.ProviderAttachment> attachments) {
        return new MailConnectorPort.ProviderMessage(
                "message-1", "thread-1", "inbox-folder",
                Instant.parse("2026-09-17T01:02:03Z"),
                "Provider Sender <sender@outside.test>",
                List.of("Inbox <inbox@example.test>"), "Provider subject",
                "<p>Provider HTML</p>", MailConnectorPort.BodyFormat.HTML,
                attachments, Map.of("classification", "CONFIDENTIAL"));
    }
}
