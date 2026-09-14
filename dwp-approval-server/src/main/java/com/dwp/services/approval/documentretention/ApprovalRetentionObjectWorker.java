package com.dwp.services.approval.documentretention;

import com.dwp.services.approval.attachment.ApprovalAttachmentStorage.Stored;
import java.util.UUID;

/** No runtime scheduler is registered before the cross-owner fences are installed. */
public final class ApprovalRetentionObjectWorker {
    private final ApprovalRetentionObjectJobs jobs;
    private final ApprovalRetentionStorage storage;
    public ApprovalRetentionObjectWorker(ApprovalRetentionObjectJobs jobs,ApprovalRetentionStorage storage){this.jobs=jobs;this.storage=storage;}
    public boolean runOne(UUID claimId) {
        var job=jobs.claim(claimId);if(job==null)return false;
        String locator=storage.locatorSha256();boolean absent=false;
        try {
            if(job.locator()!=null && !locator.equals(job.locator())) throw new IllegalStateException("Storage locator changed");
            Stored stored=job.version()==null?storage.reconcile(job.key(),job.size(),job.sha256()):new Stored(job.key(),job.version(),job.size(),job.sha256());
            if(!job.presenceVerified()) {storage.verifyPresence(stored);jobs.bind(job,stored.versionId(),locator);}
            absent=storage.deleteAndConfirmAbsent(stored);
        } catch(RuntimeException unknown) { /* UNKNOWN is durable and must not become false deletion proof. */ }
        jobs.finish(job,locator,absent);return true;
    }
}
