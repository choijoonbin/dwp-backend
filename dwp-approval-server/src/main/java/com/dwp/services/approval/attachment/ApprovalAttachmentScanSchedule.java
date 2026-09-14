package com.dwp.services.approval.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="dwp.approval.attachments",name="enabled",havingValue="true")
public class ApprovalAttachmentScanSchedule {
    private static final Logger LOG=LoggerFactory.getLogger(ApprovalAttachmentScanSchedule.class);
    private final ApprovalAttachmentScanWorker worker;
    public ApprovalAttachmentScanSchedule(ApprovalAttachmentScanWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${dwp.approval.attachments.scan-delay-ms:1000}",initialDelayString="${dwp.approval.attachments.scan-initial-delay-ms:5000}")
    public void scan(){try{worker.runOne();}catch(RuntimeException failure){LOG.warn("Approval attachment scan lease deferred; cause={}",failure.getClass().getSimpleName());}}
}
