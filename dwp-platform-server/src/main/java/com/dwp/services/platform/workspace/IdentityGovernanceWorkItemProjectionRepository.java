package com.dwp.services.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Idempotent, privacy-bounded projection of Auth-owned review assignments. */
@Repository
public class IdentityGovernanceWorkItemProjectionRepository {

    static final String SOURCE_SYSTEM = "IDENTITY_GOVERNANCE";

    private final JdbcTemplate jdbc;

    public IdentityGovernanceWorkItemProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean assigned(
            Long tenantId,
            UUID eventId,
            long sequence,
            UUID workItemRef,
            Long reviewerUserId,
            Instant dueAt) {
        UUID workItemId = projectionId(tenantId, workItemRef);
        String workKey = "IDR-" + workItemRef.toString().replace("-", "");
        return jdbc.update("""
                INSERT INTO wrk_items (
                    work_item_id, tenant_id, work_key, title_ko, title_en,
                    summary_ko, summary_en, work_type, priority, lifecycle_state,
                    owner_name, assignee_user_id, due_at, source_system,
                    source_reference, source_route, reason_ko, reason_en,
                    recommended_next_ko, recommended_next_en,
                    latest_activity_ko, latest_activity_en,
                    source_event_id, source_event_sequence)
                VALUES (
                    ?, ?, ?, '접근 권한 검토', 'Review assigned access',
                    '지정된 접근 권한 항목을 검토하고 결정을 기록하세요.',
                    'Review the assigned access item and record a decision.',
                    'REVIEW', 'HIGH', 'DUE_SOON', 'Identity Governance', ?, ?,
                    ?, ?, ?,
                    '접근 권한 유지 여부에 대한 검토가 지정되었습니다.',
                    'An access-retention review was assigned to you.',
                    '항목 근거를 확인한 후 유지 또는 회수를 결정하세요.',
                    'Inspect the evidence, then keep or revoke the access.',
                    'Identity가 검토자 배정을 확인했습니다.',
                    'Identity confirmed the reviewer assignment.', ?, ?)
                ON CONFLICT (tenant_id, source_system, source_reference)
                    WHERE source_reference IS NOT NULL
                DO UPDATE SET
                    assignee_user_id = EXCLUDED.assignee_user_id,
                    due_at = EXCLUDED.due_at,
                    lifecycle_state = 'DUE_SOON',
                    source_route = EXCLUDED.source_route,
                    latest_activity_ko = EXCLUDED.latest_activity_ko,
                    latest_activity_en = EXCLUDED.latest_activity_en,
                    source_event_id = EXCLUDED.source_event_id,
                    source_event_sequence = EXCLUDED.source_event_sequence,
                    version = wrk_items.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                  WHERE wrk_items.source_event_sequence < EXCLUDED.source_event_sequence
                """,
                workItemId,
                tenantId,
                workKey,
                reviewerUserId,
                Timestamp.from(dueAt),
                SOURCE_SYSTEM,
                workItemRef.toString(),
                "/work/queue?item=" + workItemRef,
                eventId,
                sequence) == 1;
    }

    public boolean decided(
            Long tenantId,
            UUID eventId,
            long sequence,
            UUID workItemRef,
            String decision) {
        return jdbc.update("""
                UPDATE wrk_items
                   SET lifecycle_state = 'COMPLETED',
                       latest_activity_ko = ?, latest_activity_en = ?,
                       source_event_id = ?, source_event_sequence = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND source_system = ? AND source_reference = ?
                   AND source_event_sequence < ?
                """,
                "APPROVE".equals(decision)
                        ? "접근 유지 결정이 기록되었습니다."
                        : "접근 회수 결정이 기록되었습니다.",
                "APPROVE".equals(decision)
                        ? "The keep-access decision was recorded."
                        : "The revoke-access decision was recorded.",
                eventId,
                sequence,
                tenantId,
                SOURCE_SYSTEM,
                workItemRef.toString(),
                sequence) == 1;
    }

    public boolean revoked(
            Long tenantId,
            UUID eventId,
            long sequence,
            UUID workItemRef) {
        return jdbc.update("""
                UPDATE wrk_items
                   SET lifecycle_state = 'COMPLETED', assignee_user_id = NULL,
                       latest_activity_ko = '검토자 배정이 회수되었습니다.',
                       latest_activity_en = 'The reviewer assignment was revoked.',
                       source_event_id = ?, source_event_sequence = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND source_system = ? AND source_reference = ?
                   AND source_event_sequence < ?
                """, eventId, sequence, tenantId, SOURCE_SYSTEM,
                workItemRef.toString(), sequence) == 1;
    }

    static UUID projectionId(Long tenantId, UUID workItemRef) {
        return UUID.nameUUIDFromBytes(
                ("identity-governance:" + tenantId + ':' + workItemRef)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
