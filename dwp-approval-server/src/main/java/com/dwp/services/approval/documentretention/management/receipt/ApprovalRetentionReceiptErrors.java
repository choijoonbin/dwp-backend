package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class ApprovalRetentionReceiptErrors {
    public static final String METADATA_UNAVAILABLE="RETENTION_COMMAND_METADATA_UNAVAILABLE";
    private ApprovalRetentionReceiptErrors() {}
    public static MetadataUnavailable metadataUnavailable() {return new MetadataUnavailable();}
    public static final class MetadataUnavailable extends BaseException {
        private static final long serialVersionUID=1L;
        private MetadataUnavailable() {super(ErrorCode.RESOURCE_CONFLICT,"The original command has no immutable reconciliation metadata. Its outcome must not be inferred.");}
    }
}
