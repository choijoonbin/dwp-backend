package com.dwp.services.approval.attachment;

/** I/O occurs outside the short database claim/finalize transactions. */
public final class ApprovalAttachmentScanWorker {
    private final ApprovalAttachmentScanJobs jobs;
    private final ApprovalAttachmentStorage storage;
    private final ApprovalAttachmentScanner scanner;
    private final ApprovalAttachmentPassiveContent passive;
    public ApprovalAttachmentScanWorker(ApprovalAttachmentScanJobs jobs,ApprovalAttachmentStorage storage,ApprovalAttachmentScanner scanner,ApprovalAttachmentPassiveContent passive) {
        this.jobs=jobs;this.storage=storage;this.scanner=scanner;this.passive=passive;
    }
    public boolean runOne() {
        var selected=jobs.claim();if(selected.isEmpty()) return false;
        var job=selected.get();
        byte[] bytes=storage.load(job.stored());ApprovalAttachmentIntegrity.require(bytes,job.stored().sizeBytes(),job.stored().sha256());
        var content=passive.inspect(bytes,job.mediaType());
        var av=scanner.scan(bytes,job.stored().sha256());
        return jobs.finish(job,av,content);
    }
}
