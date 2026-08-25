package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessReviewWorkServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final AccessReviewWorkItemOutboxPublisher events =
            mock(AccessReviewWorkItemOutboxPublisher.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final UUID ref = UUID.randomUUID();
    private AccessReviewWorkService service;

    @BeforeEach
    void setUp() {
        service = new AccessReviewWorkService(
                jdbc, audit, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void foreignReviewerGetsTheSameNotAvailableResultAsAMissingItem() throws Exception {
        stubEvidence(99L, "ACTIVE", "ACTIVE", NOW.plusSeconds(600), "PENDING", 11L);

        assertThatThrownBy(() -> service.detail(1L, 7L, ref))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    @Test
    void revokedOrExpiredAssignmentCannotBeResurrectedByAClientFlag() throws Exception {
        stubEvidence(7L, "REVOKED", "ACTIVE", NOW.plusSeconds(600), "PENDING", 11L);

        assertThatThrownBy(() -> service.detail(1L, 7L, ref))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));

        stubEvidence(7L, "ACTIVE", "ACTIVE", NOW.minusSeconds(1), "PENDING", 11L);
        assertThatThrownBy(() -> service.detail(1L, 7L, ref))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void staleExpectedVersionProducesConflictBeforeDomainMutation() throws Exception {
        stubEvidence(7L, "ACTIVE", "ACTIVE", NOW.plusSeconds(600), "PENDING", 11L);
        var request = new AccessReviewDtos.DecisionRequest(
                "REVOKE", "Access is no longer required for this assignment.", 10L);

        assertThatThrownBy(() -> service.decide(1L, 7L, "corr", ref, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(events, never()).decided(any(), any(), any(), anyString(), anyLong());
    }

    @Test
    void successfulDecisionEmitsOwnerEventAfterTheAtomicVersionCheck() throws Exception {
        stubEvidence(7L, "ACTIVE", "ACTIVE", NOW.plusSeconds(600), "PENDING", 11L);
        when(jdbc.update(
                anyString(),
                any(Object[].class)))
                .thenReturn(1);
        var request = new AccessReviewDtos.DecisionRequest(
                "APPROVE", "Access remains required for assigned responsibilities.", 11L);

        AccessReviewDtos.WorkItemDetail result = service.decide(
                1L, 7L, "corr", ref, request);

        assertThat(result.workItemRef()).isEqualTo(ref);
        verify(events).decided(1L, ref, "corr", "APPROVE", 12L);
        verify(audit).success(
                eq(1L), eq(7L), eq("access-review.work-item.decided"),
                eq("ACCESS_REVIEW_WORK_ITEM"), eq(ref.toString()), eq("corr"),
                any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubEvidence(
            Long reviewerUserId,
            String assignmentState,
            String campaignState,
            Instant dueAt,
            String decision,
            long version) throws Exception {
        when(resultSet.getObject("work_item_ref", UUID.class)).thenReturn(ref);
        when(resultSet.getString("campaign_name")).thenReturn("Quarterly access review");
        when(resultSet.getString("reviewer_strategy")).thenReturn("NAMED_REVIEWER");
        when(resultSet.getLong("reviewer_user_id")).thenReturn(reviewerUserId);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getString("reviewer_assignment_state")).thenReturn(assignmentState);
        when(resultSet.getString("campaign_state")).thenReturn(campaignState);
        when(resultSet.getTimestamp("due_at")).thenReturn(Timestamp.from(dueAt));
        when(resultSet.getLong("subject_user_id")).thenReturn(101L);
        when(resultSet.getString("subject_display_name")).thenReturn("Assigned subject");
        when(resultSet.getString("subject_email")).thenReturn("subject@example.invalid");
        when(resultSet.getLong("role_id")).thenReturn(55L);
        when(resultSet.getString("role_code")).thenReturn("ROLE_REVIEWED");
        when(resultSet.getString("role_name")).thenReturn("Reviewed role");
        when(resultSet.getString("access_source_type")).thenReturn("DIRECT");
        when(resultSet.getString("source_key")).thenReturn(null);
        when(resultSet.getString("source_display_name")).thenReturn(null);
        when(resultSet.getTimestamp("assignment_created_at"))
                .thenReturn(Timestamp.from(NOW.minusSeconds(3600)));
        when(resultSet.getTimestamp("subject_last_sign_in_at"))
                .thenReturn(Timestamp.from(NOW.minusSeconds(1800)));
        when(resultSet.getBoolean("privileged")).thenReturn(true);
        when(resultSet.getString("recommendation")).thenReturn("REVIEW");
        when(resultSet.getString("recommendation_reason")).thenReturn("PRIVILEGED_ROLE");
        when(resultSet.getString("decision")).thenReturn(decision);
        when(resultSet.getString("decision_reason")).thenReturn(null);
        when(resultSet.getTimestamp("decided_at")).thenReturn(null);
        when(resultSet.getString("remediation_state")).thenReturn("NOT_REQUIRED");
        when(resultSet.getLong("version")).thenReturn(version);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }
}
