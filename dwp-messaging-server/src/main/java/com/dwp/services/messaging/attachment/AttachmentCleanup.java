package com.dwp.services.messaging.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AttachmentCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentCleanup.class);
    private static final int BATCH_SIZE = 100;

    private final AttachmentRepository repository;
    private final AttachmentStorage storage;

    public AttachmentCleanup(AttachmentRepository repository, AttachmentStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Scheduled(
            initialDelayString = "${dwp.messaging.attachments.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${dwp.messaging.attachments.cleanup-interval:PT5M}")
    public void removeExpiredOrphans() {
        repository.expireDueOrphans();
        for (AttachmentRepository.AttachmentRow orphan : repository.expiredOrphans(BATCH_SIZE)) {
            try {
                storage.delete(orphan.objectKey());
                repository.deleteExpired(orphan.attachmentId());
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Messaging attachment cleanup will retry attachmentId={}",
                        orphan.attachmentId(), exception);
            }
        }
        repository.purgeDownloadGrants();
    }
}
