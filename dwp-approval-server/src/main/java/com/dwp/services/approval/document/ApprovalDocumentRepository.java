package com.dwp.services.approval.document;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;

@Repository
public class ApprovalDocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalDocumentRepository(NamedParameterJdbcTemplate jdbc, ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc; this.canonical = canonical;
    }

    public Policy policy(ApprovalRequestContext.Actor actor, String scope, boolean exclusive) {
        var p = params(actor).addValue("scope", scope).addValue("policy", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO apr_document_policy_heads(tenant_id,resource_set_key,policy_id)
                SELECT :tenant,:scope,:policy FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                ON CONFLICT(tenant_id,resource_set_key) DO NOTHING
                """, p);
        UUID policyId = jdbc.query("""
                SELECT policy_id FROM apr_document_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null);
        if (policyId == null) throw ApprovalDocumentCanonical.forbidden();
        p.addValue("policy", policyId).addValue("rules", canonical.json(ApprovalDocumentPolicy.defaults()))
                .addValue("sha", canonical.fingerprint(ApprovalDocumentPolicy.defaults()));
        jdbc.update("""
                INSERT INTO apr_document_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256)
                VALUES(:tenant,:policy,0,CAST(:rules AS jsonb),:sha) ON CONFLICT DO NOTHING
                """, p);
        return jdbc.query("""
                SELECT * FROM apr_document_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope
                """ + (exclusive ? " FOR UPDATE" : " FOR SHARE"), p, r -> {
            if (!r.next()) throw ApprovalDocumentCanonical.forbidden();
            Integer pending = (Integer) r.getObject("pending_revision");
            return new Policy(policyId, scope, r.getLong("version"), revision(actor, policyId, r.getInt("published_revision")),
                    pending == null ? null : revision(actor, policyId, pending));
        });
    }

    public Policy policyForMutation(ApprovalRequestContext.Actor actor, String scope, UUID expectedPolicyId) {
        return jdbc.query("""
                SELECT head.* FROM apr_document_policy_heads head
                  JOIN apr_tenants tenant ON tenant.tenant_id=head.tenant_id
                 WHERE head.tenant_id=:tenant AND head.resource_set_key=:scope
                   AND tenant.lifecycle_state='ACTIVE'
                 FOR UPDATE OF head FOR SHARE OF tenant
                """, params(actor).addValue("scope", scope), r -> {
            if (!r.next()) throw new BaseException(ErrorCode.NOT_FOUND, "Document policy is unavailable.");
            UUID policyId = r.getObject("policy_id", UUID.class);
            if (!policyId.equals(expectedPolicyId)) throw ApprovalDocumentCanonical.forbidden();
            Integer pending = (Integer) r.getObject("pending_revision");
            return new Policy(policyId, scope, r.getLong("version"), revision(actor, policyId, r.getInt("published_revision")),
                    pending == null ? null : revision(actor, policyId, pending));
        });
    }

    private PolicyRevision revision(ApprovalRequestContext.Actor actor, UUID id, int revision) {
        return jdbc.query("""
                SELECT * FROM apr_document_policy_versions WHERE tenant_id=:tenant AND policy_id=:id AND revision=:revision
                """, params(actor).addValue("id", id).addValue("revision", revision), r -> {
            if (!r.next()) throw ApprovalDocumentCanonical.unavailable("Document policy revision is unavailable.");
            var rules = canonical.read(r.getString("rules"), Rules.class);
            if (!canonical.fingerprint(rules).equals(r.getString("rules_sha256"))) {
                throw ApprovalDocumentCanonical.unavailable("Document policy integrity check failed.");
            }
            return new PolicyRevision(revision, rules, r.getString("rules_sha256"),
                    (Long) r.getObject("maker_user_id"), instant(r, "created_at"));
        });
    }

    public void savePolicy(ApprovalRequestContext.Actor actor, Policy policy, Rules rules) {
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision),0)+1 FROM apr_document_policy_versions WHERE tenant_id=:tenant AND policy_id=:policy
                """, params(actor).addValue("policy", policy.policyId()), Integer.class);
        var p = params(actor).addValue("policy", policy.policyId()).addValue("revision", revision)
                .addValue("rules", canonical.json(rules)).addValue("sha", canonical.fingerprint(rules)).addValue("version", policy.version());
        jdbc.update("""
                INSERT INTO apr_document_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id)
                VALUES(:tenant,:policy,:revision,CAST(:rules AS jsonb),:sha,:actor)
                """, p);
        one(jdbc.update("""
                UPDATE apr_document_policy_heads SET pending_revision=:revision,version=version+1
                 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version
                """, p));
    }

    public void publishPolicy(ApprovalRequestContext.Actor actor, Policy policy, String review) {
        var pending = policy.pending();
        if (pending == null) throw ApprovalDocumentCanonical.conflict();
        if (actor.userId().equals(pending.makerUserId())) throw ApprovalDocumentCanonical.forbidden();
        var p = params(actor).addValue("policy", policy.policyId()).addValue("revision", pending.revision())
                .addValue("version", policy.version()).addValue("maker", pending.makerUserId())
                .addValue("review", review).addValue("id", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO apr_document_policy_publications(publication_id,tenant_id,policy_id,revision,version,maker_user_id,checker_user_id,review_comment)
                VALUES(:id,:tenant,:policy,:revision,:version+1,:maker,:actor,:review)
                """, p);
        one(jdbc.update("""
                UPDATE apr_document_policy_heads SET published_revision=:revision,pending_revision=NULL,version=version+1
                 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version AND pending_revision=:revision
                """, p));
    }

    public Head head(ApprovalRequestContext.Actor actor, UUID request, int days) {
        var p = params(actor).addValue("request", request).addValue("days", days);
        jdbc.update("""
                INSERT INTO apr_document_heads(tenant_id,request_id,retain_until)
                VALUES(:tenant,:request,clock_timestamp()+:days*INTERVAL '1 day') ON CONFLICT DO NOTHING
                """, p);
        return jdbc.query("""
                SELECT h.*,p.operation AS pending_operation FROM apr_document_heads h
                  LEFT JOIN apr_document_hold_proposals p ON p.tenant_id=h.tenant_id AND p.request_id=h.request_id AND p.proposal_id=h.pending_hold_id
                 WHERE h.tenant_id=:tenant AND h.request_id=:request FOR UPDATE OF h
                """, p, r -> {
            if (!r.next()) throw ApprovalDocumentOwnerRepository.hidden();
            boolean active = r.getBoolean("hold_active");
            return new Head(r.getLong("comments_version"), r.getLong("hold_version"), active,
                    r.getObject("pending_hold_id", UUID.class), active || "PLACE".equals(r.getString("pending_operation")),
                    instant(r, "retain_until"));
        });
    }

    public Comment append(ApprovalRequestContext.Actor actor, ApprovalDocumentOwnerRepository.Owner owner,
                          Head head, String text, int days) {
        var id = UUID.randomUUID();
        var p = params(actor).addValue("request", owner.requestId()).addValue("task", owner.taskId())
                .addValue("version", head.commentsVersion()).addValue("id", id).addValue("text", text).addValue("days", days);
        one(jdbc.update("""
                UPDATE apr_document_heads SET comments_version=comments_version+1,
                  retain_until=GREATEST(retain_until,clock_timestamp()+:days*INTERVAL '1 day')
                 WHERE tenant_id=:tenant AND request_id=:request AND comments_version=:version
                """, p));
        jdbc.update("""
                INSERT INTO apr_document_comments(comment_id,tenant_id,request_id,source_task_id,sequence,author_user_id,comment_text,retain_until)
                VALUES(:id,:tenant,:request,:task,:version+1,:actor,:text,clock_timestamp()+:days*INTERVAL '1 day')
                """, p);
        return comment(actor, owner.requestId(), id, head.effectiveHold());
    }

    public Comment comment(ApprovalRequestContext.Actor actor, UUID request, UUID id, boolean held) {
        return jdbc.query("""
                SELECT * FROM apr_document_comments WHERE tenant_id=:tenant AND request_id=:request AND comment_id=:id
                 AND (:held OR retain_until>clock_timestamp())
                """, params(actor).addValue("request", request).addValue("id", id).addValue("held", held),
                r -> { if (!r.next()) throw ApprovalDocumentOwnerRepository.hidden(); return comment(r); });
    }
    public Comments comments(ApprovalRequestContext.Actor actor, UUID request, Head head, int page, int size) {
        var p = params(actor).addValue("request", request).addValue("held", head.effectiveHold())
                .addValue("limit", size).addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_document_comments WHERE tenant_id=:tenant AND request_id=:request
                 AND (:held OR retain_until>clock_timestamp())
                """, p, Long.class);
        var items = jdbc.query("""
                SELECT * FROM apr_document_comments WHERE tenant_id=:tenant AND request_id=:request
                 AND (:held OR retain_until>clock_timestamp()) ORDER BY sequence LIMIT :limit OFFSET :offset
                """, p, (r, n) -> comment(r));
        return new Comments(items, total, page, size, head.commentsVersion(), Instant.now());
    }

    public List<Evidence> evidence(ApprovalRequestContext.Actor actor, UUID request) {
        return jdbc.query("""
                SELECT event_id,event_type,actor_type,actor_id,message,occurred_at FROM apr_request_events
                 WHERE tenant_id=:tenant AND request_id=:request ORDER BY occurred_at,event_id LIMIT 501
                """, params(actor).addValue("request", request), (r, n) -> new Evidence(r.getObject(1, UUID.class),
                r.getString(2), r.getString(3), r.getString(4), r.getString(5), instant(r, "occurred_at")));
    }

    public Map<String, Object> receipt(ApprovalRequestContext.Actor actor, String route, String key, Object input) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,120}")) throw ApprovalDocumentCanonical.conflict();
        var p = params(actor).addValue("route", route).addValue("key", key);
        // Serialize unknown-response retries before any effect, including simultaneous first requests.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:material,0)) IS NULL", p.addValue("material",
                actor.tenantId() + ":" + actor.userId() + ":" + route + ":" + key), Boolean.class);
        return jdbc.query("""
                SELECT fingerprint,metadata::text,retain_until FROM apr_document_command_receipts
                 WHERE tenant_id=:tenant AND actor_user_id=:actor AND route_key=:route AND idempotency_key=:key
                """, p, r -> {
            if (!r.next()) return null;
            if (!canonical.fingerprint(input).equals(r.getString(1))) throw ApprovalDocumentCanonical.conflict();
            if (!instant(r, "retain_until").isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
            @SuppressWarnings("unchecked") Map<String, Object> metadata = canonical.read(r.getString(2), Map.class);
            return metadata;
        });
    }

    public void complete(ApprovalRequestContext.Actor actor, String route, String key, Object input,
                         Map<String, Object> metadata, int retentionDays) {
        jdbc.update("""
                INSERT INTO apr_document_command_receipts(receipt_id,tenant_id,actor_user_id,route_key,idempotency_key,fingerprint,metadata,retain_until)
                VALUES(:id,:tenant,:actor,:route,:key,:fingerprint,CAST(:metadata AS jsonb),clock_timestamp()+:days*INTERVAL '1 day')
                """, params(actor).addValue("id", UUID.randomUUID()).addValue("route", route).addValue("key", key)
                .addValue("fingerprint", canonical.fingerprint(input)).addValue("metadata", canonical.json(metadata)).addValue("days", retentionDays));
    }

    public void proposeHold(ApprovalRequestContext.Actor actor, UUID request, Head head, HoldProposal input) {
        if (head.pendingHoldId() != null || (input.operation() == HoldOperation.PLACE) == head.activeHold()) {
            throw ApprovalDocumentCanonical.conflict();
        }
        var p = params(actor).addValue("request", request).addValue("version", head.holdVersion())
                .addValue("id", UUID.randomUUID()).addValue("operation", input.operation().name()).addValue("reason", input.reason());
        jdbc.update("""
                INSERT INTO apr_document_hold_proposals(proposal_id,tenant_id,request_id,operation,reason,maker_user_id,base_version)
                VALUES(:id,:tenant,:request,:operation,:reason,:actor,:version)
                """, p);
        one(jdbc.update("""
                UPDATE apr_document_heads SET pending_hold_id=:id,hold_version=hold_version+1
                 WHERE tenant_id=:tenant AND request_id=:request AND hold_version=:version AND pending_hold_id IS NULL
                """, p));
    }

    public Hold hold(ApprovalRequestContext.Actor actor, UUID request, Head head) {
        PendingHold pending = head.pendingHoldId() == null ? null : jdbc.query("""
                SELECT * FROM apr_document_hold_proposals WHERE tenant_id=:tenant AND request_id=:request AND proposal_id=:id
                """, params(actor).addValue("request", request).addValue("id", head.pendingHoldId()), r -> r.next()
                ? new PendingHold(head.pendingHoldId(), HoldOperation.valueOf(r.getString("operation")), r.getString("reason"),
                r.getLong("maker_user_id"), instant(r, "created_at")) : null);
        var journal = jdbc.query("""
                SELECT j.*,p.operation,p.maker_user_id,p.reason FROM apr_document_hold_journal j
                  JOIN apr_document_hold_proposals p ON p.tenant_id=j.tenant_id AND p.request_id=j.request_id AND p.proposal_id=j.proposal_id
                 WHERE j.tenant_id=:tenant AND j.request_id=:request ORDER BY j.version LIMIT 501
                """, params(actor).addValue("request", request), (r, n) -> new HoldEntry(r.getObject("entry_id", UUID.class),
                r.getLong("version"), HoldOperation.valueOf(r.getString("operation")), r.getLong("maker_user_id"),
                r.getLong("checker_user_id"), r.getString("reason"), r.getString("review_comment"), instant(r, "occurred_at")));
        if (journal.size() > 500) throw ApprovalDocumentCanonical.unavailable("Legal hold journal requires a bounded paged reader.");
        boolean preservationPending = head.effectiveHold() && !head.activeHold();
        return new Hold(request, head.holdVersion(), head.activeHold(), pending, journal,
                head.activeHold() ? "LEGAL_HOLD" : preservationPending ? "LEGAL_HOLD_PENDING_APPROVAL" : "PURGE_WORKER_NOT_IMPLEMENTED",
                head.retainUntil(), preservationPending, false);
    }

    public void publishHold(ApprovalRequestContext.Actor actor, UUID request, Head head, PublishHold input) {
        Hold hold = hold(actor, request, head);
        if (hold.pending() == null || !input.proposalId().equals(hold.pending().proposalId())) throw ApprovalDocumentCanonical.conflict();
        if (actor.userId() == hold.pending().makerUserId()) throw ApprovalDocumentCanonical.forbidden();
        var p = params(actor).addValue("request", request).addValue("version", head.holdVersion())
                .addValue("proposal", input.proposalId()).addValue("id", UUID.randomUUID())
                .addValue("review", input.reviewComment()).addValue("active", hold.pending().operation() == HoldOperation.PLACE);
        jdbc.update("""
                INSERT INTO apr_document_hold_journal(entry_id,tenant_id,request_id,proposal_id,version,checker_user_id,review_comment)
                VALUES(:id,:tenant,:request,:proposal,:version+1,:actor,:review)
                """, p);
        one(jdbc.update("""
                UPDATE apr_document_heads SET pending_hold_id=NULL,hold_active=:active,hold_version=hold_version+1
                 WHERE tenant_id=:tenant AND request_id=:request AND hold_version=:version AND pending_hold_id=:proposal
                """, p));
    }

    private static Comment comment(ResultSet r) throws SQLException {
        return new Comment(r.getObject("comment_id", UUID.class), r.getObject("request_id", UUID.class),
                r.getObject("source_task_id", UUID.class), r.getLong("sequence"), r.getLong("author_user_id"),
                r.getString("comment_text"), instant(r, "created_at"), instant(r, "retain_until"));
    }
    static Instant instant(ResultSet r, String column) throws SQLException { Timestamp t = r.getTimestamp(column); return t == null ? null : t.toInstant(); }
    private void one(int count) { if (count != 1) throw ApprovalDocumentCanonical.conflict(); }
    public record Head(long commentsVersion, long holdVersion, boolean activeHold, UUID pendingHoldId,
                       boolean effectiveHold, Instant retainUntil) { }
}
