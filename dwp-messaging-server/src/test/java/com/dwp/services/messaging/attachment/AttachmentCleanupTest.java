package com.dwp.services.messaging.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentCleanupTest {

    @Mock
    private AttachmentRepository repository;
    @Mock
    private AttachmentStorage storage;

    @Test
    void removesExpiredObjectsAndTheirOneUseGrants() {
        UUID attachmentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AttachmentRepository.AttachmentRow orphan = new AttachmentRepository.AttachmentRow(
                attachmentId, 1, conversationId, 42, null,
                "report.txt", "report.txt", "txt", "text/plain", "text/plain",
                6, "1/" + conversationId + "/" + attachmentId, "a".repeat(64),
                "EXPIRED", null, "b".repeat(64), "c".repeat(64),
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().minusMinutes(2), 4);
        when(repository.expiredOrphans(100)).thenReturn(List.of(orphan));

        new AttachmentCleanup(repository, storage).removeExpiredOrphans();

        verify(repository).expireDueOrphans();
        verify(storage).delete(orphan.objectKey());
        verify(repository).deleteExpired(attachmentId);
        verify(repository).purgeDownloadGrants();
    }
}
