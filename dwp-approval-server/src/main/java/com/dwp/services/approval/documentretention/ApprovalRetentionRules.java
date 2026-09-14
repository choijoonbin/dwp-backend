package com.dwp.services.approval.documentretention;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ApprovalRetentionRules(boolean allowPurge, List<String> allowedClassifications,
        int recordRetentionDays, int deletedDraftRecoveryDays, int receiptRetentionDays,
        int holdEvidenceRetentionDays, int auditEvidenceRetentionDays,
        int maxInventoryRows, int maxObjectsPerRecord) {
    public ApprovalRetentionRules {
        allowedClassifications = List.copyOf(allowedClassifications);
        if (allowedClassifications.isEmpty() || allowedClassifications.size() > 3
                || new HashSet<>(allowedClassifications).size() != allowedClassifications.size()
                || !Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED").containsAll(allowedClassifications)) {
            throw new IllegalArgumentException("Known distinct classifications are required");
        }
        for (int days : new int[]{recordRetentionDays, deletedDraftRecoveryDays, receiptRetentionDays,
                holdEvidenceRetentionDays, auditEvidenceRetentionDays}) {
            if (days < 1 || days > 3650) throw new IllegalArgumentException("Retention days exceed bounds");
        }
        if (maxInventoryRows < 1 || maxInventoryRows > 50000 || maxObjectsPerRecord < 1 || maxObjectsPerRecord > 1000) {
            throw new IllegalArgumentException("Record inventory exceeds bounds");
        }
    }

    public static ApprovalRetentionRules defaults() {
        return new ApprovalRetentionRules(false, List.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED"),
                365, 30, 365, 365, 365, 50000, 1000);
    }
}
