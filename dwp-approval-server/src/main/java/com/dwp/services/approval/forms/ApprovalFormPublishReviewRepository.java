package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.PublishReviewQueue;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.PublishReviewQueueItem;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.PublishReviewRequest;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalFormPublishReviewRepository {
    private final ApprovalFormWorkspaceRepository forms;

    public ApprovalFormPublishReviewRepository(ApprovalFormWorkspaceRepository forms) {
        this.forms = forms;
    }

    public PublishReviewRequest latest(Actor actor, UUID formId) {
        return latest(actor, formId, false);
    }

    public PublishReviewRequest latest(Actor actor, UUID formId, boolean lock) {
        List<PublishReviewRequest> rows = forms.jdbc.query("""
            SELECT r.* FROM apr_form_publish_review_requests r
             JOIN apr_forms f ON f.tenant_id=r.tenant_id AND f.form_id=r.form_id
             WHERE r.tenant_id=:tenant AND r.form_id=:form
               AND r.management_resource_set_key=:scope
               AND f.management_resource_set_key=:scope
             ORDER BY r.requested_at DESC, r.review_request_id DESC LIMIT 1
            """ + (lock ? " FOR UPDATE OF r" : ""), forms.params(actor, formId),
                (row, number) -> request(row));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public PublishReviewRequest pending(Actor actor, UUID formId, UUID requestId, boolean lock) {
        List<PublishReviewRequest> rows = forms.jdbc.query("""
            SELECT r.* FROM apr_form_publish_review_requests r
             JOIN apr_forms f ON f.tenant_id=r.tenant_id AND f.form_id=r.form_id
             WHERE r.tenant_id=:tenant AND r.form_id=:form AND r.review_request_id=:request
               AND r.management_resource_set_key=:scope
               AND f.management_resource_set_key=:scope AND r.request_status='PENDING'
            """ + (lock ? " FOR UPDATE OF r" : ""),
            forms.params(actor, formId).addValue("request", requestId),
            (row, number) -> request(row));
        if (rows.size() != 1) throw hidden();
        return rows.getFirst();
    }

    public PublishReviewQueue assigned(Actor actor, int size) {
        var parameters = forms.params(actor, UUID.randomUUID()).addValue("size", size + 1);
        List<PublishReviewQueueItem> rows = forms.jdbc.query("""
            SELECT r.*, f.form_key, f.name_ko, f.name_en
              FROM apr_form_publish_review_requests r
              JOIN apr_forms f ON f.tenant_id=r.tenant_id AND f.form_id=r.form_id
              JOIN apr_form_workspaces w ON w.tenant_id=r.tenant_id AND w.form_id=r.form_id
                AND w.draft_form_version_id=r.draft_form_version_id
                AND w.workspace_version=r.workspace_revision
              JOIN apr_form_versions v ON v.tenant_id=r.tenant_id AND v.form_id=r.form_id
                AND v.form_version_id=r.draft_form_version_id
                AND v.lifecycle_state='DRAFT' AND v.schema_sha256=r.schema_sha256
             WHERE r.tenant_id=:tenant AND r.management_resource_set_key=:scope
               AND f.management_resource_set_key=:scope AND f.version=r.form_revision
               AND r.reviewer_user_id=:actor AND r.request_status='PENDING'
             ORDER BY r.requested_at ASC, r.review_request_id ASC LIMIT :size
            """, parameters, (row, number) -> new PublishReviewQueueItem(
                    request(row), row.getString("form_key"), row.getString("name_ko"), row.getString("name_en")));
        boolean truncated = rows.size() > size;
        return new PublishReviewQueue(truncated ? rows.subList(0, size) : rows, truncated, OffsetDateTime.now());
    }

    public PublishReviewRequest insert(Actor actor, ApprovalFormWorkspaceRepository.Head head,
            ApprovalFormLifecycleDtos.Version draft, UUID requestId, long reviewerUserId,
            UUID reviewerPersonId, String digest, String reason) {
        var parameters = forms.params(actor, head.formId())
                .addValue("request", requestId)
                .addValue("draft", draft.formVersionId())
                .addValue("base", head.published())
                .addValue("maker", draft.createdBy())
                .addValue("editor", head.editor())
                .addValue("reviewer", reviewerUserId)
                .addValue("person", reviewerPersonId)
                .addValue("revision", head.revision())
                .addValue("workspace", head.workspaceRevision())
                .addValue("schema", draft.schemaSha256())
                .addValue("digest", digest)
                .addValue("reason", reason);
        try {
            forms.jdbc.update("""
                INSERT INTO apr_form_publish_review_requests(
                    tenant_id,review_request_id,form_id,draft_form_version_id,base_published_form_version_id,
                    management_resource_set_key,maker_user_id,last_editor_user_id,reviewer_user_id,
                    reviewer_person_public_id,form_revision,workspace_revision,schema_sha256,
                    review_content_sha256,request_reason)
                VALUES(:tenant,:request,:form,:draft,:base,:scope,:maker,:editor,:reviewer,
                    :person,:revision,:workspace,:schema,:digest,:reason)
                """, parameters);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        return pending(actor, head.formId(), requestId, false);
    }

    public void supersedePending(Actor actor, UUID formId) {
        forms.jdbc.update("""
            UPDATE apr_form_publish_review_requests
               SET request_status='SUPERSEDED',request_version=request_version+1,
                   decided_at=clock_timestamp(),decided_by=:actor,
                   decision_reason='The working draft changed after review was requested.'
             WHERE tenant_id=:tenant AND form_id=:form AND management_resource_set_key=:scope
               AND request_status='PENDING'
            """, forms.params(actor, formId));
    }

    public PublishReviewRequest transition(Actor actor, PublishReviewRequest expected,
            String status, String reason) {
        var parameters = forms.params(actor, expected.formId())
                .addValue("request", expected.reviewRequestId())
                .addValue("version", expected.version())
                .addValue("status", status)
                .addValue("reason", reason);
        int changed = forms.jdbc.update("""
            UPDATE apr_form_publish_review_requests
               SET request_status=:status,request_version=request_version+1,
                   decided_at=clock_timestamp(),decided_by=:actor,decision_reason=:reason
             WHERE tenant_id=:tenant AND form_id=:form AND review_request_id=:request
               AND management_resource_set_key=:scope AND request_status='PENDING'
               AND request_version=:version AND reviewer_user_id=:actor
            """, parameters);
        if (changed != 1) throw conflict();
        List<PublishReviewRequest> rows = forms.jdbc.query("""
            SELECT r.* FROM apr_form_publish_review_requests r
             WHERE r.tenant_id=:tenant AND r.form_id=:form AND r.review_request_id=:request
               AND r.management_resource_set_key=:scope
            """, parameters, (row, number) -> request(row));
        if (rows.size() != 1) throw hidden();
        return rows.getFirst();
    }

    private PublishReviewRequest request(ResultSet row) throws SQLException {
        return new PublishReviewRequest(
                row.getObject("review_request_id", UUID.class),
                row.getObject("form_id", UUID.class),
                row.getObject("draft_form_version_id", UUID.class),
                row.getObject("base_published_form_version_id", UUID.class),
                row.getString("request_status"), row.getLong("request_version"),
                row.getLong("maker_user_id"), row.getLong("last_editor_user_id"),
                row.getLong("reviewer_user_id"), row.getObject("reviewer_person_public_id", UUID.class),
                row.getLong("form_revision"), row.getLong("workspace_revision"),
                row.getString("schema_sha256"), row.getString("review_content_sha256"),
                row.getString("request_reason"), row.getObject("requested_at", OffsetDateTime.class),
                row.getObject("decided_at", OffsetDateTime.class), row.getObject("decided_by", Long.class),
                row.getString("decision_reason"));
    }

    private BaseException hidden() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException conflict() {
        return ApprovalFormWorkspaceRepository.conflict();
    }
}
