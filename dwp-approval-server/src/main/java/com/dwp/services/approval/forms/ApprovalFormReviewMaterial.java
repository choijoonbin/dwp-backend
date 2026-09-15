package com.dwp.services.approval.forms;

import java.util.LinkedHashMap;

final class ApprovalFormReviewMaterial {
    private ApprovalFormReviewMaterial() { }

    static String digest(ApprovalFormWorkspaceRepository forms,
            ApprovalFormWorkspaceRepository.Head head,
            ApprovalFormLifecycleDtos.Version draft) {
        var value = new LinkedHashMap<String, Object>();
        value.put("formId", head.formId().toString());
        value.put("formRevision", head.revision());
        value.put("workspaceRevision", head.workspaceRevision());
        value.put("draftFormVersionId", draft.formVersionId().toString());
        value.put("basePublishedVersionId", head.published() == null ? null : head.published().toString());
        value.put("sourceVersionId", draft.sourceVersionId() == null ? null : draft.sourceVersionId().toString());
        value.put("materialDigest", draft.materialDigest());
        value.put("makerUserId", draft.createdBy());
        value.put("lastEditorUserId", head.editor());
        return forms.codec.sha(forms.codec.json(value));
    }
}
