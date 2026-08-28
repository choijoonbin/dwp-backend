package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentGrant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentPermission;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReview;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReviewDecision;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RunState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RetentionHealth;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RetentionPurgeResult;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.SourceArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.StoredRun;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VideoMeetingIntelligenceRepository {

    private final JdbcTemplate jdbc;

    public VideoMeetingIntelligenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SourceArtifact> sourceTranscript(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_state, sha256,
                       retention_until, server_side_processing_allowed, processing_region,
                       content_notice_id, consent_snapshot_sha256
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'TRANSCRIPT'
                """, this::sourceArtifact, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    public ConsentEvidence consentEvidence(long tenantId, UUID meetingId, UUID noticeId) {
        List<ConsentRow> rows = jdbc.query("""
                SELECT participant.participant_id,
                       (ack.acknowledgement_id IS NOT NULL) AS acknowledged
                  FROM vm_meeting_participants participant
                  LEFT JOIN vm_meeting_content_notice_acknowledgements ack
                    ON ack.tenant_id = participant.tenant_id
                   AND ack.meeting_id = participant.meeting_id
                   AND ack.participant_id = participant.participant_id
                   AND ack.notice_id = ?
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.attendance_state IN ('ADMITTED', 'JOINED', 'LEFT')
                 ORDER BY participant.participant_id
                """, (rs, row) -> new ConsentRow(
                        rs.getObject("participant_id", UUID.class),
                        rs.getBoolean("acknowledged")),
                noticeId, tenantId, meetingId);
        int acknowledged = (int) rows.stream().filter(ConsentRow::acknowledged).count();
        String material = noticeId + "|" + rows.stream()
                .map(row -> row.participantId() + ":" + row.acknowledged())
                .collect(java.util.stream.Collectors.joining("|"));
        return new ConsentEvidence(rows.size(), acknowledged, sha256(material));
    }

    public Optional<StoredRun> byIdempotency(
            long tenantId,
            UUID meetingId,
            long requestedBy,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_runs
                 WHERE tenant_id = ? AND meeting_id = ? AND requested_by = ?
                   AND idempotency_key = ?
                """, (rs, row) -> new StoredRun(
                        rs.getString("request_sha256"), run(rs, row)),
                tenantId, meetingId, requestedBy, idempotencyKey).stream().findFirst();
    }

    public Optional<IntelligenceRun> tryCreateRunning(IntelligenceRun run) {
        return jdbc.query("""
                    INSERT INTO vm_meeting_intelligence_runs (
                        run_id, tenant_id, meeting_id, source_artifact_id, source_sha256,
                        content_notice_id, consent_snapshot_sha256, analysis_profile,
                        output_language, processing_region, execution_fence,
                        lease_expires_at, attempt_count, run_state, provider_code,
                        provider_model, prompt_version, schema_version, idempotency_key,
                        request_sha256, requested_at, requested_by, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING *
                    """, this::run,
                    run.runId(), run.tenantId(), run.meetingId(), run.sourceArtifactId(),
                    run.sourceSha256(), run.contentNoticeId(), run.consentSnapshotSha256(),
                    run.analysisProfile(), run.outputLanguage(), run.processingRegion(),
                    run.executionFence(), run.leaseExpiresAt(), run.attemptCount(),
                    run.providerCode(), run.providerModel(), run.promptVersion(),
                    run.schemaVersion(), run.idempotencyKey(), run.requestSha256(),
                    run.requestedAt(), run.requestedBy(), run.startedAt())
                    .stream().findFirst();
    }

    public Optional<IntelligenceRun> activeForSource(
            long tenantId,
            UUID meetingId,
            UUID sourceArtifactId,
            String sourceSha256,
            String analysisProfile,
            UUID contentNoticeId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_runs
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND source_artifact_id = ? AND source_sha256 = ?
                   AND analysis_profile = ? AND content_notice_id = ?
                   AND run_state = 'RUNNING'
                """, this::run, tenantId, meetingId, sourceArtifactId,
                sourceSha256, analysisProfile, contentNoticeId)
                .stream().findFirst();
    }

    public Optional<IntelligenceRun> run(long tenantId, UUID meetingId, UUID runId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_runs
                 WHERE tenant_id = ? AND meeting_id = ? AND run_id = ?
                """, this::run, tenantId, meetingId, runId).stream().findFirst();
    }

    public Optional<IntelligenceRun> reclaimExpired(
            IntelligenceRun current,
            UUID newFence,
            OffsetDateTime reclaimedAt,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_intelligence_runs
                   SET execution_fence = ?, lease_expires_at = ?,
                       attempt_count = attempt_count + 1,
                       started_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND run_id = ?
                   AND run_state = 'RUNNING' AND execution_fence = ?
                   AND version = ? AND lease_expires_at <= ?
                RETURNING *
                """, this::run,
                newFence, leaseExpiresAt, reclaimedAt, reclaimedAt,
                current.tenantId(), current.meetingId(), current.runId(),
                current.executionFence(), current.version(), reclaimedAt)
                .stream().findFirst();
    }

    public IntelligenceRun succeed(
            IntelligenceRun current,
            String providerCode,
            String providerModel,
            OffsetDateTime completedAt) {
        return transitionRun(
                current, "SUCCEEDED", null, providerCode, providerModel, completedAt);
    }

    public IntelligenceRun fail(
            IntelligenceRun current, String failureCode, OffsetDateTime completedAt) {
        return transitionRun(
                current, "FAILED", failureCode,
                current.providerCode(), current.providerModel(), completedAt);
    }

    public IntelligenceReport createDraft(
            UUID reportId,
            IntelligenceRun run,
            String encryptedPayload,
            String payloadSha256,
            OffsetDateTime retentionUntil,
            long actorUserId,
            OffsetDateTime createdAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_intelligence_reports (
                    report_id, tenant_id, meeting_id, run_id, report_state, audience,
                    encrypted_payload, payload_sha256, source_sha256, schema_version,
                    retention_until, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'DRAFT', 'PRIVATE_REVIEWERS', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, this::report,
                reportId, run.tenantId(), run.meetingId(), run.runId(), encryptedPayload,
                payloadSha256, run.sourceSha256(), run.schemaVersion(), retentionUntil,
                createdAt, actorUserId, createdAt, actorUserId)
                .stream().findFirst().orElseThrow();
    }

    public Optional<IntelligenceReport> report(
            long tenantId, UUID meetingId, UUID reportId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_reports
                 WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                """, this::report, tenantId, meetingId, reportId).stream().findFirst();
    }

    public Optional<IntelligenceReport> reportForRun(
            long tenantId, UUID meetingId, UUID runId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_reports
                 WHERE tenant_id = ? AND meeting_id = ? AND run_id = ?
                """, this::report, tenantId, meetingId, runId).stream().findFirst();
    }

    public Optional<IntelligenceReport> latestVisibleReport(
            long tenantId,
            UUID meetingId,
            long userId,
            boolean host,
            OffsetDateTime now) {
        return jdbc.query("""
                SELECT report.*
                  FROM vm_meeting_intelligence_reports report
                 WHERE report.tenant_id = ? AND report.meeting_id = ?
                   AND report.report_state <> 'DELETED'
                   AND (report.legal_hold = TRUE OR report.retention_until > ?)
                   AND (? OR report.report_state = 'PUBLISHED'
                        OR EXISTS (
                            SELECT 1 FROM vm_meeting_content_acl acl
                             WHERE acl.tenant_id = report.tenant_id
                               AND acl.meeting_id = report.meeting_id
                               AND acl.content_type = 'INTELLIGENCE_REPORT'
                               AND acl.content_id = report.report_id
                               AND acl.principal_user_id = ?
                               AND acl.permission IN ('VIEW', 'REVIEW', 'MANAGE')
                               AND acl.revoked_at IS NULL
                               AND (acl.expires_at IS NULL OR acl.expires_at > ?)))
                 ORDER BY report.created_at DESC, report.report_id DESC
                 LIMIT 1
                """, this::report,
                tenantId, meetingId, now, host, userId, now).stream().findFirst();
    }

    public IntelligenceReport review(
            IntelligenceReport current,
            ReviewDecision decision,
            long reviewerUserId,
            OffsetDateTime reviewedAt) {
        String target = decision == ReviewDecision.APPROVE ? "APPROVED" : "REJECTED";
        return jdbc.query("""
                UPDATE vm_meeting_intelligence_reports
                   SET report_state = ?,
                       approved_at = CASE WHEN ? = 'APPROVED' THEN ? ELSE NULL END,
                       approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE NULL END,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                   AND report_state = 'DRAFT' AND version = ?
                RETURNING *
                """, this::report,
                target, target, reviewedAt, target, reviewerUserId, reviewedAt,
                reviewerUserId, current.tenantId(), current.meetingId(),
                current.reportId(), current.version()).stream().findFirst()
                .orElseThrow(this::versionConflict);
    }

    public IntelligenceReview saveReview(
            IntelligenceReport reviewed,
            long reviewedVersion,
            String reviewedPayloadSha256,
            ReviewDecision decision,
            String reasonCode,
            long reviewerUserId,
            OffsetDateTime reviewedAt) {
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reviews (
                    review_id, tenant_id, meeting_id, report_id, reviewed_report_version,
                    reviewed_payload_sha256, decision, reason_code, reviewed_at, reviewed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reviewId, reviewed.tenantId(), reviewed.meetingId(), reviewed.reportId(),
                reviewedVersion, reviewedPayloadSha256, decision.name(), reasonCode,
                reviewedAt, reviewerUserId);
        return new IntelligenceReview(
                reviewId, reviewed.reportId(), reviewedVersion, reviewedPayloadSha256,
                decision, reasonCode, reviewedAt, reviewerUserId);
    }

    public List<IntelligenceReview> reviews(
            long tenantId, UUID meetingId, UUID reportId) {
        return jdbc.query("""
                SELECT review_id, report_id, reviewed_report_version,
                       reviewed_payload_sha256, decision, reason_code,
                       reviewed_at, reviewed_by
                  FROM vm_meeting_intelligence_reviews
                 WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                 ORDER BY reviewed_at, review_id
                """, this::review, tenantId, meetingId, reportId);
    }

    public IntelligenceReport publish(
            IntelligenceReport current,
            long publisherUserId,
            OffsetDateTime publishedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_intelligence_reports
                   SET report_state = 'PUBLISHED', audience = 'MEETING_PARTICIPANTS',
                       published_at = ?, published_by = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                   AND report_state = 'APPROVED' AND version = ?
                RETURNING *
                """, this::report,
                publishedAt, publisherUserId, publishedAt, publisherUserId,
                current.tenantId(), current.meetingId(), current.reportId(), current.version())
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    public IntelligenceReport delete(
            IntelligenceReport current,
            long actorUserId,
            OffsetDateTime deletedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_intelligence_reports
                   SET report_state = 'DELETED', audience = 'PRIVATE_REVIEWERS',
                       encrypted_payload = NULL, payload_sha256 = NULL,
                       approved_at = NULL, approved_by = NULL,
                       published_at = NULL, published_by = NULL,
                       deleted_at = ?, deleted_by = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                   AND report_state <> 'DELETED' AND legal_hold = FALSE AND version = ?
                RETURNING *
                """, this::report,
                deletedAt, actorUserId, deletedAt, actorUserId,
                current.tenantId(), current.meetingId(), current.reportId(), current.version())
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    public boolean hasPermission(
            long tenantId,
            UUID meetingId,
            UUID reportId,
            long userId,
            List<ContentPermission> permissions,
            OffsetDateTime now) {
        if (permissions.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(
                permissions.size(), "?"));
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>(List.of(
                tenantId, meetingId, reportId, userId, now));
        parameters.addAll(permissions.stream().map(Enum::name).toList());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_content_acl
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND content_type = 'INTELLIGENCE_REPORT' AND content_id = ?
                   AND principal_user_id = ? AND revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > ?)
                   AND permission IN (""" + placeholders + ")",
                Integer.class, parameters.toArray());
        return count != null && count > 0;
    }

    public ContentGrant grant(
            IntelligenceReport report,
            long principalUserId,
            ContentPermission permission,
            OffsetDateTime expiresAt,
            String reasonCode,
            long grantedBy,
            OffsetDateTime grantedAt) {
        UUID aclId = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO vm_meeting_content_acl (
                    acl_id, tenant_id, meeting_id, content_type, content_id,
                    principal_user_id, permission, granted_at, granted_by,
                    expires_at, reason_code)
                VALUES (?, ?, ?, 'INTELLIGENCE_REPORT', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, meeting_id, content_type, content_id,
                             principal_user_id, permission) WHERE revoked_at IS NULL
                DO UPDATE SET expires_at = EXCLUDED.expires_at,
                              reason_code = EXCLUDED.reason_code,
                              granted_at = EXCLUDED.granted_at,
                              granted_by = EXCLUDED.granted_by
                RETURNING *
                """, this::grant,
                aclId, report.tenantId(), report.meetingId(), report.reportId(),
                principalUserId, permission.name(), grantedAt, grantedBy,
                expiresAt, reasonCode).stream().findFirst().orElseThrow();
    }

    public void revoke(
            IntelligenceReport report,
            long principalUserId,
            ContentPermission permission,
            long revokedBy,
            OffsetDateTime revokedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_content_acl
                   SET revoked_at = ?, revoked_by = ?
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND content_type = 'INTELLIGENCE_REPORT' AND content_id = ?
                   AND principal_user_id = ? AND permission = ? AND revoked_at IS NULL
                """, revokedAt, revokedBy, report.tenantId(), report.meetingId(),
                report.reportId(), principalUserId, permission.name());
        if (updated == 0) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The content grant was not found.");
        }
    }

    public RetentionPurgeResult purgeExpiredReports(
            OffsetDateTime now,
            int batchSize,
            String workerId,
            UUID fenceToken) {
        List<String> lease = jdbc.query("""
                SELECT health_key
                  FROM vm_meeting_intelligence_retention_health
                 WHERE health_key = 'REPORT_RETENTION'
                   AND active_fence = ? AND active_lease_expires_at > ?
                 FOR UPDATE
                """, (rs, row) -> rs.getString("health_key"), fenceToken, now);
        if (lease.isEmpty()) {
            throw new IllegalStateException("Retention worker fence is not active.");
        }
        List<IntelligenceReport> candidates = jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_reports
                 WHERE report_state <> 'DELETED' AND legal_hold = FALSE
                   AND retention_until <= ?
                 ORDER BY retention_until, report_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
                """, this::report, now, batchSize);
        int deleted = 0;
        for (IntelligenceReport report : candidates) {
            int updated = jdbc.update("""
                    UPDATE vm_meeting_intelligence_reports
                       SET report_state = 'DELETED', audience = 'PRIVATE_REVIEWERS',
                           encrypted_payload = NULL, payload_sha256 = NULL,
                           approved_at = NULL, approved_by = NULL,
                           published_at = NULL, published_by = NULL,
                           deleted_at = ?, deleted_by = 0, version = version + 1,
                           updated_at = ?, updated_by = 0
                     WHERE tenant_id = ? AND meeting_id = ? AND report_id = ?
                       AND version = ? AND report_state <> 'DELETED'
                       AND legal_hold = FALSE AND retention_until <= ?
                    """, now, now, report.tenantId(), report.meetingId(), report.reportId(),
                    report.version(), now);
            if (updated == 1) {
                jdbc.update("""
                        INSERT INTO vm_meeting_intelligence_deletions (
                            deletion_id, tenant_id, meeting_id, report_id,
                            previous_report_state, previous_payload_sha256,
                            deletion_reason, fence_token, deleted_at, worker_id)
                        VALUES (?, ?, ?, ?, ?, ?, 'RETENTION_EXPIRED', ?, ?, ?)
                        """, UUID.randomUUID(), report.tenantId(), report.meetingId(),
                        report.reportId(), report.state().name(), report.payloadSha256(),
                        fenceToken, now, workerId);
                deleted++;
            }
        }
        Boolean overdueRemaining = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM vm_meeting_intelligence_reports
                     WHERE report_state <> 'DELETED' AND legal_hold = FALSE
                       AND retention_until <= ?)
                """, Boolean.class, now);
        return new RetentionPurgeResult(deleted, Boolean.TRUE.equals(overdueRemaining));
    }

    public Optional<RetentionHealth> retentionHealth() {
        return jdbc.query("""
                SELECT last_attempt_at, last_success_at, last_failure_at,
                       last_failure_code, active_fence,
                       active_lease_expires_at, version
                  FROM vm_meeting_intelligence_retention_health
                 WHERE health_key = 'REPORT_RETENTION'
                """, (rs, row) -> new RetentionHealth(
                        rs.getObject("last_attempt_at", OffsetDateTime.class),
                        rs.getObject("last_success_at", OffsetDateTime.class),
                        rs.getObject("last_failure_at", OffsetDateTime.class),
                        rs.getString("last_failure_code"),
                        rs.getObject("active_fence", UUID.class),
                        rs.getObject("active_lease_expires_at", OffsetDateTime.class),
                        rs.getLong("version")))
                .stream().findFirst();
    }

    public boolean tryMarkRetentionAttempt(
            OffsetDateTime attemptedAt,
            OffsetDateTime leaseExpiresAt,
            UUID fence) {
        return !jdbc.query("""
                UPDATE vm_meeting_intelligence_retention_health
                   SET last_failure_at = CASE
                           WHEN active_fence IS NOT NULL THEN ? ELSE last_failure_at END,
                       last_failure_code = CASE
                           WHEN active_fence IS NOT NULL
                           THEN 'RETENTION_LEASE_EXPIRED' ELSE last_failure_code END,
                       last_attempt_at = ?, active_fence = ?,
                       active_lease_expires_at = ?,
                       version = version + 1
                 WHERE health_key = 'REPORT_RETENTION'
                   AND (active_fence IS NULL OR active_lease_expires_at <= ?)
                RETURNING health_key
                """, (rs, row) -> rs.getString("health_key"),
                attemptedAt, attemptedAt, fence, leaseExpiresAt, attemptedAt)
                .isEmpty();
    }

    public void markRetentionSuccess(
            OffsetDateTime succeededAt, UUID fence, boolean backlogClear) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_intelligence_retention_health
                   SET last_success_at = ?,
                       last_failure_at = CASE
                           WHEN ? THEN NULL ELSE last_failure_at END,
                       last_failure_code = CASE
                           WHEN ? THEN NULL ELSE last_failure_code END,
                       active_fence = NULL, active_lease_expires_at = NULL,
                       version = version + 1
                 WHERE health_key = 'REPORT_RETENTION' AND active_fence = ?
                   AND active_lease_expires_at > ?
                """, succeededAt, backlogClear, backlogClear, fence, succeededAt);
        if (updated != 1) throw new IllegalStateException("Retention worker fence was lost.");
    }

    public void markRetentionFailure(
            OffsetDateTime failedAt, UUID fence, String failureCode) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_intelligence_retention_health
                   SET last_failure_at = ?, last_failure_code = ?,
                       active_fence = NULL, active_lease_expires_at = NULL,
                       version = version + 1
                 WHERE health_key = 'REPORT_RETENTION' AND active_fence = ?
                """, failedAt, failureCode, fence);
        if (updated != 1) throw new IllegalStateException("Retention worker fence was lost.");
    }

    private IntelligenceRun transitionRun(
            IntelligenceRun current,
            String state,
            String failureCode,
            String providerCode,
            String providerModel,
            OffsetDateTime completedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_intelligence_runs
                   SET run_state = ?, failure_code = ?, provider_code = ?,
                       provider_model = ?, completed_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND run_id = ?
                   AND run_state = 'RUNNING' AND execution_fence = ? AND version = ?
                   AND lease_expires_at > ?
                RETURNING *
                """, this::run,
                state, failureCode, providerCode, providerModel, completedAt, completedAt,
                current.tenantId(), current.meetingId(), current.runId(),
                current.executionFence(), current.version(), completedAt)
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    private SourceArtifact sourceArtifact(ResultSet rs, int row) throws SQLException {
        return new SourceArtifact(
                rs.getObject("artifact_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class), rs.getString("artifact_state"),
                rs.getString("sha256"), rs.getObject("retention_until", OffsetDateTime.class),
                rs.getBoolean("server_side_processing_allowed"),
                rs.getString("processing_region"),
                rs.getObject("content_notice_id", UUID.class),
                rs.getString("consent_snapshot_sha256"));
    }

    private IntelligenceRun run(ResultSet rs, int row) throws SQLException {
        return new IntelligenceRun(
                rs.getObject("run_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class),
                rs.getObject("source_artifact_id", UUID.class), rs.getString("source_sha256"),
                rs.getObject("content_notice_id", UUID.class),
                rs.getString("consent_snapshot_sha256"), rs.getString("analysis_profile"),
                rs.getString("output_language"), rs.getString("processing_region"),
                rs.getObject("execution_fence", UUID.class),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getInt("attempt_count"),
                RunState.valueOf(rs.getString("run_state")), rs.getString("provider_code"),
                rs.getString("provider_model"), rs.getString("prompt_version"),
                rs.getString("schema_version"), rs.getString("idempotency_key"),
                rs.getString("request_sha256"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getLong("requested_by"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("failure_code"), rs.getLong("version"));
    }

    private IntelligenceReport report(ResultSet rs, int row) throws SQLException {
        return new IntelligenceReport(
                rs.getObject("report_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class), rs.getObject("run_id", UUID.class),
                ReportState.valueOf(rs.getString("report_state")),
                Audience.valueOf(rs.getString("audience")), rs.getString("encrypted_payload"),
                rs.getString("payload_sha256"), rs.getString("source_sha256"),
                rs.getString("schema_version"),
                rs.getObject("retention_until", OffsetDateTime.class),
                rs.getBoolean("legal_hold"),
                rs.getObject("approved_at", OffsetDateTime.class), nullableLong(rs, "approved_by"),
                rs.getObject("published_at", OffsetDateTime.class), nullableLong(rs, "published_by"),
                rs.getObject("deleted_at", OffsetDateTime.class), nullableLong(rs, "deleted_by"),
                rs.getLong("version"), rs.getLong("created_by"));
    }

    private IntelligenceReview review(ResultSet rs, int row) throws SQLException {
        return new IntelligenceReview(
                rs.getObject("review_id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getLong("reviewed_report_version"),
                rs.getString("reviewed_payload_sha256"),
                ReviewDecision.valueOf(rs.getString("decision")),
                rs.getString("reason_code"),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getLong("reviewed_by"));
    }

    private ContentGrant grant(ResultSet rs, int row) throws SQLException {
        return new ContentGrant(
                rs.getObject("acl_id", UUID.class), rs.getObject("content_id", UUID.class),
                rs.getLong("principal_user_id"),
                ContentPermission.valueOf(rs.getString("permission")),
                rs.getObject("granted_at", OffsetDateTime.class), rs.getLong("granted_by"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("reason_code"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The meeting intelligence resource changed. Refresh and retry.");
    }

    private record ConsentRow(UUID participantId, boolean acknowledged) {
    }
}
