package com.dwp.services.approval.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository
public class ApprovalAttachmentScanJobs {
    private record Candidate(long tenantId,UUID requestId) { }
    public record Job(UUID uploadId,long tenantId,UUID requestId,long actorId,UUID policyId,long policyVersion,
            UUID token,long generation,ApprovalAttachmentStorage.Stored stored,String mediaType) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalAttachmentAudit audit;
    public ApprovalAttachmentScanJobs(NamedParameterJdbcTemplate jdbc,ApprovalIdentityDirectory identities,ApprovalAttachmentAudit audit) {this.jdbc=jdbc;this.identities=identities;this.audit=audit;}
    @Transactional
    public Optional<Job> claim() {
        Candidate request=jdbc.query("""
                SELECT request.tenant_id,request.request_id FROM apr_requests request
                 WHERE EXISTS(SELECT 1 FROM apr_attachment_uploads upload
                   WHERE upload.tenant_id=request.tenant_id AND upload.request_id=request.request_id
                     AND (upload.state='QUARANTINED' OR (upload.state='SCANNING' AND upload.lease_until<=clock_timestamp()))
                     AND upload.attempts<5 AND upload.object_version IS NOT NULL AND upload.expires_at>clock_timestamp())
                   AND %s ORDER BY request.request_id,request.tenant_id FOR UPDATE OF request SKIP LOCKED LIMIT 1
                """.formatted(ApprovalRetentionLiveGuard.LIVE),
                Map.of(),r->r.next()?new Candidate(r.getLong(1),r.getObject(2,UUID.class)):null);
        if(request==null) return Optional.empty();
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(request.tenantId(),request.requestId());
        UUID token=UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT upload_id FROM apr_attachment_uploads
                   WHERE tenant_id=:tenant AND request_id=:request
                     AND (state='QUARANTINED' OR (state='SCANNING' AND lease_until<=clock_timestamp()))
                     AND attempts<5 AND object_version IS NOT NULL AND expires_at>clock_timestamp()
                   ORDER BY created_at,upload_id FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE apr_attachment_uploads u SET state='SCANNING',generation=generation+1,
                    version=version+1,attempts=attempts+1,lease_token=:token,lease_until=clock_timestamp()+INTERVAL '90 seconds',updated_at=clock_timestamp()
                  FROM candidate c WHERE u.upload_id=c.upload_id RETURNING u.*
                """,new MapSqlParameterSource("token",token).addValue("tenant",request.tenantId()).addValue("request",request.requestId()),r->r.next()?Optional.of(new Job(r.getObject("upload_id",UUID.class),r.getLong("tenant_id"),
                r.getObject("request_id",UUID.class),r.getLong("uploader_user_id"),r.getObject("policy_id",UUID.class),r.getLong("policy_version"),token,r.getLong("generation"),
                new ApprovalAttachmentStorage.Stored(r.getString("object_key"),r.getString("object_version"),r.getLong("size_bytes"),r.getString("content_sha256")),r.getString("media_type"))):Optional.empty());
    }
    @Transactional
    public boolean finish(Job job,ApprovalAttachmentScanner.Result av,ApprovalAttachmentPassiveContent.Result passive) {
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(job.tenantId(),job.requestId());
        var p=params(job);
        Boolean live=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_attachment_uploads WHERE upload_id=:upload AND tenant_id=:tenant AND state='SCANNING' AND generation=:generation AND lease_token=:token AND lease_until>clock_timestamp())",p,Boolean.class);
        if (!Boolean.TRUE.equals(live)) return false;
        boolean entitled=current(job);
        if (!job.stored().sha256().equals(av.contentSha256())) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,"Scan content binding changed.");
        if (av.verdict()==ApprovalAttachmentScanner.Verdict.AV_CLEAR && (av.engineVersion()==null || av.definitionsAt()==null || av.scannedAt()==null))
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,"Scanner evidence is incomplete.");
        boolean available=entitled && av.verdict()==ApprovalAttachmentScanner.Verdict.AV_CLEAR && passive.allowed();
        String state=available?"AVAILABLE":av.verdict()==ApprovalAttachmentScanner.Verdict.INDETERMINATE && entitled?"QUARANTINED":"REJECTED";
        String reason=!entitled?"CURRENT_AUTHORITY_REVOKED":!passive.allowed()?passive.reason():av.reason();
        int changed=jdbc.update("""
                UPDATE apr_attachment_uploads SET state=:state,version=version+1,lease_token=NULL,lease_until=NULL,
                  reason=:reason,av_state=:av,passive_content_state=:passive,engine_version=:engine,definitions_at=:definitions,
                  scanned_at=:scanned,parser_version=:parser,updated_at=clock_timestamp()
                 WHERE upload_id=:upload AND tenant_id=:tenant AND state='SCANNING' AND lease_token=:token AND generation=:generation
                   AND lease_until>clock_timestamp() AND object_version=:objectVersion AND content_sha256=:sha
                """,p.addValue("state",state).addValue("reason",reason).addValue("av",av.verdict().name())
                .addValue("passive",passive.allowed()?"PASSIVE_ALLOWED":"REJECTED").addValue("engine",av.engineVersion())
                .addValue("definitions",av.definitionsAt()==null?null:java.sql.Timestamp.from(av.definitionsAt()))
                .addValue("scanned",java.sql.Timestamp.from(av.scannedAt())).addValue("parser",passive.parserVersion()));
        boolean stillEntitled=current(job);
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(job.tenantId(),job.requestId());
        if (entitled && !stillEntitled) throw new BaseException(ErrorCode.FORBIDDEN,"Attachment authority changed during scan finalization.");
        if (changed==1) audit.record(job.tenantId(),job.actorId(),job.requestId(),"APPROVAL_ATTACHMENT_SCAN_FINALIZED",job.token().toString(),
                Map.of("uploadId",job.uploadId(),"generation",job.generation(),"state",state,"sha256",job.stored().sha256(),"reason",reason));
        return changed==1;
    }
    private boolean current(Job job) {
        var subject=identities.require(job.tenantId(),job.actorId());
        if (subject==null) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Current scan owner is unavailable.");
        boolean permitted=Long.valueOf(job.tenantId()).equals(subject.tenantId()) && Long.valueOf(job.actorId()).equals(subject.userId()) && subject.active()
                && subject.roles()!=null && subject.roles().stream().noneMatch(role->role.startsWith("PROVIDER_"))
                && subject.hasPermission("APP.APPROVALS:VIEW") && subject.hasPermission("ACTION.APPROVAL_REQUEST:VIEW") && subject.hasPermission("ACTION.APPROVAL_REQUEST:UPDATE");
        Boolean bound=jdbc.query("""
                SELECT r.requester_user_id=:actor AND r.status IN('DRAFT','NEEDS_INFO') AND h.version=:policyVersion
                       AND v.rules->>'allowUpload'='true' AND jsonb_exists(v.rules->'allowedMediaTypes',:media)
                  FROM apr_requests r JOIN apr_tenants t ON t.tenant_id=r.tenant_id
                  JOIN apr_attachment_policy_heads h ON h.tenant_id=r.tenant_id AND h.resource_set_key=r.management_resource_set_key AND h.policy_id=:policy
                  JOIN apr_attachment_policy_versions v ON v.tenant_id=h.tenant_id AND v.policy_id=h.policy_id AND v.revision=h.published_revision
                 WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.deleted_at IS NULL AND t.lifecycle_state='ACTIVE'
                 FOR SHARE OF r,t,h
                """,params(job),r->r.next() && r.getBoolean(1));
        return permitted && Boolean.TRUE.equals(bound);
    }
    private MapSqlParameterSource params(Job job) {return new MapSqlParameterSource().addValue("tenant",job.tenantId()).addValue("actor",job.actorId()).addValue("request",job.requestId())
            .addValue("upload",job.uploadId()).addValue("policy",job.policyId()).addValue("policyVersion",job.policyVersion()).addValue("token",job.token())
            .addValue("generation",job.generation()).addValue("objectVersion",job.stored().versionId()).addValue("sha",job.stored().sha256()).addValue("media",job.mediaType());}
}
