package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.AutoRequest;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.RequestState;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.RunBinding;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;

@Repository
class MeetingIntelligenceAutoRequestRepository {

    static final String DEFAULT_OUTPUT_LANGUAGE = "ko-KR";
    private final JdbcTemplate jdbc;

    MeetingIntelligenceAutoRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID enqueue(
            Meeting meeting,
            ContentPlan plan,
            ContentNotice notice,
            TranscriptArtifact artifact,
            OffsetDateTime now) {
        String identity = requestHash(
                "meeting-intelligence-auto-request-v1", meeting.tenantId(),
                meeting.meetingId(), artifact.artifactId(), artifact.sourceSha256(),
                notice.noticeId(), plan.version(), meeting.organizerUserId());
        UUID requestId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.US_ASCII));
        String idempotencyKey = idempotencyKey(requestId, 1);
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_auto_requests (
                    request_id, tenant_id, meeting_id, source_artifact_id,
                    source_sha256, content_notice_id, expected_content_plan_version,
                    requested_by, output_language, processing_region,
                    intelligence_idempotency_key,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                """, requestId, meeting.tenantId(), meeting.meetingId(),
                artifact.artifactId(), artifact.sourceSha256(), notice.noticeId(),
                plan.version(), meeting.organizerUserId(), DEFAULT_OUTPUT_LANGUAGE,
                artifact.processingRegion(), idempotencyKey, now, now, now);
        AutoRequest stored = byId(requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "The automatic intelligence request was not persisted."));
        if (stored.tenantId() != meeting.tenantId()
                || !stored.meetingId().equals(meeting.meetingId())
                || !stored.sourceArtifactId().equals(artifact.artifactId())
                || !stored.sourceSha256().equals(artifact.sourceSha256())
                || !stored.contentNoticeId().equals(notice.noticeId())
                || stored.expectedContentPlanVersion() != plan.version()
                || stored.requestedBy() != meeting.organizerUserId()) {
            throw new IllegalStateException(
                    "The automatic intelligence request identity collided.");
        }
        return requestId;
    }

    Optional<AutoRequest> claim(
            OffsetDateTime now, OffsetDateTime leaseExpiresAt, UUID fence) {
        List<AutoRequest> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT request_id
                      FROM vm_meeting_intelligence_auto_requests
                     WHERE (request_state = 'PENDING' AND available_at <= ?)
                        OR (request_state = 'RUNNING' AND lease_expires_at <= ?)
                     ORDER BY available_at, created_at, request_id
                     LIMIT 1
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE vm_meeting_intelligence_auto_requests request
                   SET request_state = 'RUNNING', execution_fence = ?,
                       lease_expires_at = ?, attempt_count = attempt_count + 1,
                       updated_at = ?, version = version + 1
                  FROM candidate
                 WHERE request.request_id = candidate.request_id
                RETURNING request.*
                """, this::map, now, now, fence, leaseExpiresAt, now);
        return claimed.stream().findFirst();
    }

    Optional<AutoRequest> byId(UUID requestId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_auto_requests
                 WHERE request_id = ?
                """, this::map, requestId).stream().findFirst();
    }

    Optional<AutoRequest> lock(UUID requestId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_intelligence_auto_requests
                 WHERE request_id = ? FOR UPDATE
                """, this::map, requestId).stream().findFirst();
    }

    AutoRequest retry(
            AutoRequest current,
            String failureCode,
            OffsetDateTime now,
            OffsetDateTime availableAt) {
        int generation = current.executionGeneration() + 1;
        int updated = jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET request_state = 'PENDING', execution_fence = NULL,
                       lease_expires_at = NULL, available_at = ?,
                       execution_generation = ?, intelligence_idempotency_key = ?,
                       last_failure_code = ?, updated_at = ?, version = version + 1
                 WHERE request_id = ? AND request_state = 'RUNNING'
                   AND execution_fence = ? AND version = ? AND lease_expires_at > ?
                """, availableAt, generation,
                idempotencyKey(current.requestId(), generation), failureCode, now,
                current.requestId(), current.executionFence(), current.version(), now);
        requireUpdated(updated);
        return byId(current.requestId()).orElseThrow();
    }

    AutoRequest succeed(
            AutoRequest current, UUID runId, OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET request_state = 'SUCCEEDED', execution_fence = NULL,
                       lease_expires_at = NULL, run_id = ?, last_failure_code = NULL,
                       completed_at = ?, updated_at = ?, version = version + 1
                 WHERE request_id = ? AND request_state = 'RUNNING'
                   AND execution_fence = ? AND version = ? AND lease_expires_at > ?
                """, runId, completedAt, completedAt, current.requestId(),
                current.executionFence(), current.version(), completedAt);
        requireUpdated(updated);
        return byId(current.requestId()).orElseThrow();
    }

    AutoRequest fail(
            AutoRequest current,
            UUID runId,
            String failureCode,
            OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET request_state = 'FAILED', execution_fence = NULL,
                       lease_expires_at = NULL, run_id = ?, last_failure_code = ?,
                       completed_at = ?, updated_at = ?, version = version + 1
                 WHERE request_id = ? AND request_state = 'RUNNING'
                   AND execution_fence = ? AND version = ? AND lease_expires_at > ?
                """, runId, failureCode, completedAt, completedAt,
                current.requestId(), current.executionFence(), current.version(), completedAt);
        requireUpdated(updated);
        return byId(current.requestId()).orElseThrow();
    }

    Optional<RunBinding> runBinding(long tenantId, UUID meetingId, UUID runId) {
        return jdbc.query("""
                SELECT run.run_id, run.tenant_id, run.meeting_id,
                       run.source_artifact_id, run.source_sha256,
                       run.content_notice_id, run.output_language,
                       run.idempotency_key, run.request_sha256,
                       run.requested_by, run.run_state, run.failure_code,
                       report.report_id
                  FROM vm_meeting_intelligence_runs run
                  LEFT JOIN vm_meeting_intelligence_reports report
                    ON report.tenant_id = run.tenant_id
                   AND report.meeting_id = run.meeting_id
                   AND report.run_id = run.run_id
                 WHERE run.tenant_id = ? AND run.meeting_id = ? AND run.run_id = ?
                """, (rs, rowNumber) -> new RunBinding(
                        rs.getObject("run_id", UUID.class), rs.getLong("tenant_id"),
                        rs.getObject("meeting_id", UUID.class),
                        rs.getObject("source_artifact_id", UUID.class),
                        rs.getString("source_sha256"),
                        rs.getObject("content_notice_id", UUID.class),
                        rs.getString("output_language"), rs.getString("idempotency_key"),
                        rs.getString("request_sha256"), rs.getLong("requested_by"),
                        rs.getString("run_state"), rs.getString("failure_code"),
                        rs.getObject("report_id", UUID.class)),
                tenantId, meetingId, runId).stream().findFirst();
    }

    Optional<RunBinding> runByIdempotency(AutoRequest request) {
        return jdbc.query("""
                SELECT run.run_id, run.tenant_id, run.meeting_id,
                       run.source_artifact_id, run.source_sha256,
                       run.content_notice_id, run.output_language,
                       run.idempotency_key, run.request_sha256,
                       run.requested_by, run.run_state, run.failure_code,
                       report.report_id
                  FROM vm_meeting_intelligence_runs run
                  LEFT JOIN vm_meeting_intelligence_reports report
                    ON report.tenant_id = run.tenant_id
                   AND report.meeting_id = run.meeting_id
                   AND report.run_id = run.run_id
                 WHERE run.tenant_id = ? AND run.meeting_id = ?
                   AND run.requested_by = ? AND run.idempotency_key = ?
                """, (rs, rowNumber) -> new RunBinding(
                        rs.getObject("run_id", UUID.class), rs.getLong("tenant_id"),
                        rs.getObject("meeting_id", UUID.class),
                        rs.getObject("source_artifact_id", UUID.class),
                        rs.getString("source_sha256"),
                        rs.getObject("content_notice_id", UUID.class),
                        rs.getString("output_language"), rs.getString("idempotency_key"),
                        rs.getString("request_sha256"), rs.getLong("requested_by"),
                        rs.getString("run_state"), rs.getString("failure_code"),
                        rs.getObject("report_id", UUID.class)),
                request.tenantId(), request.meetingId(), request.requestedBy(),
                request.intelligenceIdempotencyKey()).stream().findFirst();
    }

    private AutoRequest map(ResultSet rs, int rowNumber) throws SQLException {
        return new AutoRequest(
                rs.getObject("request_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class),
                rs.getObject("source_artifact_id", UUID.class),
                rs.getString("source_sha256"),
                rs.getObject("content_notice_id", UUID.class),
                rs.getLong("expected_content_plan_version"),
                rs.getLong("requested_by"), rs.getString("output_language"),
                rs.getString("processing_region"),
                rs.getString("intelligence_idempotency_key"),
                RequestState.valueOf(rs.getString("request_state")),
                rs.getObject("execution_fence", UUID.class),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getInt("execution_generation"), rs.getInt("attempt_count"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getObject("run_id", UUID.class), rs.getString("last_failure_code"),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String idempotencyKey(UUID requestId, int generation) {
        return "auto-recap:" + requestId + ":" + generation;
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "The automatic intelligence execution fence changed or expired.");
        }
    }
}
