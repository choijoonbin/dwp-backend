package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalQueryRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void scopesCompletedTasksToTheRecordedDecisionActor() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        ApprovalQueryRepository repository = new ApprovalQueryRepository(jdbc, new ObjectMapper());
        ApprovalRequestContext.Actor actor = new ApprovalRequestContext.Actor(
                17L,
                42L,
                null,
                "Decision maker",
                Set.of("FINANCE_APPROVERS"),
                Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:VIEW"));

        repository.tasks(actor, "COMPLETED", 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("task.decision_actor_user_id = :userId")
                .contains("task.status IN ('APPROVED', 'REJECTED')")
                .contains("ORDER BY task.completed_at DESC NULLS LAST, task.created_at DESC")
                .doesNotContain("delegation.delegate_user_id = :userId");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsActorAndStepSnapshotsForTheDecisionTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        ApprovalQueryRepository repository = new ApprovalQueryRepository(jdbc, new ObjectMapper());

        repository.timeline(17L, java.util.UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("event_data ->> 'actorDisplayName'")
                .contains("event_data ->> 'stepName'")
                .contains("event_data ->> 'stepSequence'")
                .contains("event_data ->> 'delegated'");
    }
}
