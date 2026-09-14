package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.documentretention.ApprovalRetentionRules;
import org.springframework.stereotype.Component;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;

@Component
public final class ApprovalRetentionPolicyValidator {
    public ApprovalRetentionRules validate(PublicRules rules) {
        if (rules==null || rules.allowPurge()==null || rules.allowedClassifications()==null
                || rules.recordRetentionDays()==null || rules.deletedDraftRecoveryDays()==null
                || rules.receiptRetentionDays()==null || rules.holdEvidenceRetentionDays()==null
                || rules.auditEvidenceRetentionDays()==null || rules.maxInventoryRows()==null
                || rules.maxObjectsPerRecord()==null) throw ApprovalRetentionErrors.invalid();
        try {
            return new ApprovalRetentionRules(rules.allowPurge(),rules.allowedClassifications(),
                    rules.recordRetentionDays(),rules.deletedDraftRecoveryDays(),rules.receiptRetentionDays(),
                    rules.holdEvidenceRetentionDays(),rules.auditEvidenceRetentionDays(),
                    rules.maxInventoryRows(),rules.maxObjectsPerRecord());
        } catch (IllegalArgumentException exception) { throw ApprovalRetentionErrors.invalid(); }
    }
    public PublicRules defaults() {
        var r=ApprovalRetentionRules.defaults();
        return new PublicRules(r.allowPurge(),r.allowedClassifications(),r.recordRetentionDays(),
                r.deletedDraftRecoveryDays(),r.receiptRetentionDays(),r.holdEvidenceRetentionDays(),
                r.auditEvidenceRetentionDays(),r.maxInventoryRows(),r.maxObjectsPerRecord());
    }
}
