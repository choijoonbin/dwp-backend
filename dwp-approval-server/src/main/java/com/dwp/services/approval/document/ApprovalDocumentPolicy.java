package com.dwp.services.approval.document;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public final class ApprovalDocumentPolicy {
    public static ApprovalDocumentDtos.Rules defaults() {
        return new ApprovalDocumentDtos.Rules(true, false, false, false, false, false,
                List.of(), List.of(), 20, 1048576, 300, 365);
    }
    public void validate(ApprovalDocumentDtos.Rules rules) {
        if (rules == null || rules.allowedClassifications() == null || rules.fields() == null
                || rules.maxBatchItems() < 1 || rules.maxBatchItems() > 50 || rules.maxBytes() < 1024 || rules.maxBytes() > 5242880
                || rules.snapshotTtlSeconds() < 60 || rules.snapshotTtlSeconds() > 3600
                || rules.evidenceRetentionDays() < 1 || rules.evidenceRetentionDays() > 3650
                || !Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED").containsAll(rules.allowedClassifications())
                || new HashSet<>(rules.allowedClassifications()).size() != rules.allowedClassifications().size()
                || rules.allowedClassifications().size() > 3
                || ((rules.allowPrint() || rules.allowJsonExport() || rules.allowArchiveExport()) && rules.allowedClassifications().isEmpty())) {
            throw invalid();
        }
        fields(rules.fields(), 0, new int[]{0});
    }
    private void fields(List<ApprovalDocumentDtos.FieldRule> fields, int depth, int[] count) {
        if (fields == null || fields.size() > 100) throw invalid();
        if (fields.isEmpty()) return;
        if (depth > 4) throw invalid();
        var names = new HashSet<String>();
        for (var field : fields) {
            if (field == null || field.key() == null || !field.key().matches("[A-Za-z][A-Za-z0-9_]{0,79}")
                    || !names.add(field.key()) || ++count[0] > 100 || field.type() == null
                    || field.maxLength() < 1 || field.maxLength() > 10000 || field.children() == null
                    || (!Set.of(ApprovalDocumentDtos.FieldType.OBJECT, ApprovalDocumentDtos.FieldType.OBJECT_LIST).contains(field.type()) && !field.children().isEmpty())
                    || (field.type() == ApprovalDocumentDtos.FieldType.OBJECT_LIST
                        ? field.maxRows() == null || field.maxRows() < 1 || field.maxRows() > 50 : field.maxRows() != null)) throw invalid();
            fields(field.children(), depth + 1, count);
        }
    }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Document policy fields or bounds are invalid."); }
}
