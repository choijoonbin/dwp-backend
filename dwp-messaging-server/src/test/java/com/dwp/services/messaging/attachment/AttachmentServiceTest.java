package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentRepository repository;
    @Mock
    private AttachmentStorage storage;
    @Mock
    private AttachmentScanner scanner;

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void nonMemberCannotCreateAnUploadSession() {
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject(100));
        when(repository.activeMember(1, conversationId, 100)).thenReturn(false);

        assertThatThrownBy(() -> service().createUpload(
                conversationId,
                new AttachmentDtos.CreateUploadRequest(
                        "report.pdf", "application/pdf", 100, UUID.randomUUID()),
                null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(repository, never()).maximumAttachmentMb(anyLong());
    }

    @Test
    void anotherUserCannotReuseTheUploadSession() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MessagingRequestContext.set(subject(200));
        when(repository.activeMember(1, conversationId, 200)).thenReturn(true);
        when(repository.findOwned(1, conversationId, 200, attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().upload(
                conversationId, attachmentId, "a".repeat(64), new byte[10], null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void uploadMovesThroughScanningAndDeletesRejectedBytes() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] content = "unsafe-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MessagingRequestContext.set(subject(100));
        AttachmentRepository.AttachmentRow pending = row(
                attachmentId, conversationId, 100, "QUARANTINED", 4, null, null);
        AttachmentRepository.AttachmentRow scanning = row(
                attachmentId, conversationId, 100, "SCANNING", 5,
                AttachmentSecurity.contentHash(content), null);
        AttachmentRepository.AttachmentRow rejected = row(
                attachmentId, conversationId, 100, "REJECTED", 6,
                AttachmentSecurity.contentHash(content), null);
        when(repository.activeMember(1, conversationId, 100)).thenReturn(true);
        when(repository.findOwned(1, conversationId, 100, attachmentId))
                .thenReturn(Optional.of(pending));
        when(repository.beginScan(
                anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                anyLong(), anyLong())).thenReturn(Optional.of(scanning));
        AttachmentScanner.ScanResult verdict = AttachmentScanner.ScanResult.rejected(
                "text/plain", "MALWARE_DETECTED");
        when(scanner.scan(any(), any())).thenReturn(verdict);
        when(repository.completeScan(1, attachmentId, 5, verdict)).thenReturn(rejected);

        AttachmentDtos.AttachmentSummary result = service().upload(
                conversationId, attachmentId, "a".repeat(64), content, "corr");

        assertThat(result.status()).isEqualTo("REJECTED");
        var ordered = inOrder(repository, storage, scanner);
        ordered.verify(repository).beginScan(
                anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                anyLong(), anyLong());
        ordered.verify(storage).store(pending.objectKey(), content);
        ordered.verify(scanner).scan(any(), any());
        verify(storage).delete(pending.objectKey());
    }

    @Test
    void aConsumedUploadTokenNeverOverwritesTheWinningObject() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] content = "unsafe-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MessagingRequestContext.set(subject(100));
        AttachmentRepository.AttachmentRow pending = row(
                attachmentId, conversationId, 100, "QUARANTINED", 4, null, null);
        when(repository.activeMember(1, conversationId, 100)).thenReturn(true);
        when(repository.findOwned(1, conversationId, 100, attachmentId))
                .thenReturn(Optional.of(pending));
        when(repository.beginScan(
                anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().upload(
                conversationId, attachmentId, "a".repeat(64), content, null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(storage, never()).store(anyString(), any());
        verify(storage, never()).delete(anyString());
        verify(scanner, never()).scan(any(), any());
    }

    @Test
    void scannerFailureRemovesQuarantinedBytes() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] content = "unsafe-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MessagingRequestContext.set(subject(100));
        AttachmentRepository.AttachmentRow pending = row(
                attachmentId, conversationId, 100, "QUARANTINED", 4, null, null);
        AttachmentRepository.AttachmentRow scanning = row(
                attachmentId, conversationId, 100, "SCANNING", 5,
                AttachmentSecurity.contentHash(content), null);
        when(repository.activeMember(1, conversationId, 100)).thenReturn(true);
        when(repository.findOwned(1, conversationId, 100, attachmentId))
                .thenReturn(Optional.of(pending));
        when(repository.beginScan(
                anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                anyLong(), anyLong())).thenReturn(Optional.of(scanning));
        when(scanner.scan(any(), any())).thenThrow(new IllegalStateException("scanner unavailable"));

        assertThatThrownBy(() -> service().upload(
                conversationId, attachmentId, "a".repeat(64), content, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scanner unavailable");

        verify(storage).delete(scanning.objectKey());
    }

    @Test
    void scanningAttachmentCannotReceiveADownloadGrant() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MessagingRequestContext.set(subject(100));
        when(repository.activeMember(1, conversationId, 100)).thenReturn(true);
        when(repository.findVisible(1, conversationId, 100, attachmentId))
                .thenReturn(Optional.of(row(
                        attachmentId, conversationId, 100, "SCANNING", 1, null, null)));

        assertThatThrownBy(() -> service().createDownloadGrant(
                conversationId, attachmentId, null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));

        verify(repository, never()).createDownloadGrant(
                any(), any(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void uploaderCanDiscardAnUnattachedCleanFile() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        MessagingRequestContext.set(subject(100));
        AttachmentRepository.AttachmentRow clean = row(
                attachmentId, conversationId, 100, "CLEAN", 7, "c".repeat(64), null);
        when(repository.activeMember(1, conversationId, 100)).thenReturn(true);
        when(repository.expireOwnedUnattached(1, conversationId, 100, attachmentId))
                .thenReturn(Optional.of(clean));

        service().discard(conversationId, attachmentId, "corr");

        verify(storage).delete(clean.objectKey());
        verify(repository).deleteExpired(attachmentId);
        verify(repository).audit(
                1, 100, "messaging.attachment.discarded", attachmentId, "corr");
    }

    private AttachmentService service() {
        return new AttachmentService(repository, storage, scanner,
                new AttachmentProperties(
                        "local", Path.of("build/test-attachments"), "local",
                        Duration.ofMinutes(10), Duration.ofMinutes(1),
                        100, 2,
                        "localhost", 3310, Duration.ofSeconds(1)));
    }

    private MessagingRequestContext.Subject subject(long userId) {
        return new MessagingRequestContext.Subject(
                userId, 1, UUID.randomUUID(), "User",
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
    }

    private AttachmentRepository.AttachmentRow row(
            UUID attachmentId,
            UUID conversationId,
            long uploader,
            String status,
            long version,
            String contentHash,
            UUID messageId) {
        return new AttachmentRepository.AttachmentRow(
                attachmentId, 1, conversationId, uploader, messageId,
                "report.txt", "report.txt", "txt", "text/plain", "text/plain",
                14, "1/" + conversationId + "/" + attachmentId, contentHash,
                status, "REJECTED".equals(status) ? "MALWARE_DETECTED" : null,
                "b".repeat(64), AttachmentSecurity.hash("a".repeat(64)),
                OffsetDateTime.now().plusMinutes(10),
                OffsetDateTime.now(), version);
    }
}
