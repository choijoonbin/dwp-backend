package com.dwp.services.auth.service;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessReviewWorkItemOutboxPublisherTest {

    @Test
    void lifecycleEventTypesAreValidSharedRegistryIdentifiers() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();

        new AccessReviewWorkItemOutboxPublisher(
                mock(JdbcTemplate.class),
                mock(DomainEventRecorder.class),
                contracts,
                new ObjectMapper());

        assertThat(contracts.snapshot().keySet()).containsExactlyInAnyOrder(
                AccessReviewWorkItemOutboxPublisher.ASSIGNED,
                AccessReviewWorkItemOutboxPublisher.DECIDED,
                AccessReviewWorkItemOutboxPublisher.REVOKED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignedEventContainsOnlyOpaqueQueueProjectionData() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry contracts = mock(DomainEventContractRegistry.class);
        ResultSet result = mock(ResultSet.class);
        UUID campaignId = UUID.randomUUID();
        UUID ref = UUID.randomUUID();
        Instant dueAt = Instant.parse("2026-08-25T03:00:00Z");
        when(result.getObject("work_item_ref", UUID.class)).thenReturn(ref);
        when(result.getLong("reviewer_user_id")).thenReturn(7L);
        when(result.getTimestamp("due_at")).thenReturn(Timestamp.from(dueAt));
        when(result.getString("decision")).thenReturn("PENDING");
        when(result.getString("reviewer_assignment_state")).thenReturn("ACTIVE");
        when(result.getLong("version")).thenReturn(0L);
        when(result.getLong("work_event_sequence")).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(campaignId)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var publisher = new AccessReviewWorkItemOutboxPublisher(
                jdbc, recorder, contracts, new ObjectMapper().findAndRegisterModules());

        publisher.assignedForCampaign(1L, campaignId, "corr-1");

        ArgumentCaptor<DomainEventEnvelope> event =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().type())
                .isEqualTo(AccessReviewWorkItemOutboxPublisher.ASSIGNED);
        assertThat(event.getValue().aggregateId()).isEqualTo(ref.toString());
        assertThat(event.getValue().aggregateSequence()).isEqualTo(1L);
        assertThat(event.getValue().data().path("objectVersion").longValue()).isZero();
        Set<String> fields = event.getValue().data().propertyStream()
                .map(entry -> entry.getKey())
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder(
                "workItemRef", "reviewerUserId", "dueAt", "decision",
                "assignmentState", "objectVersion");
        assertThat(event.getValue().data().toString())
                .doesNotContain("campaignId", "itemId", "email", "displayName", "roleId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void decidedUsesTheDurableEventSequenceAndKeepsTheActualObjectVersion() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        ResultSet result = projection(
                UUID.randomUUID(), "REVOKE", "ACTIVE", 12L, 1L);
        UUID ref = result.getObject("work_item_ref", UUID.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var publisher = publisher(jdbc, recorder);

        publisher.decided(1L, ref, "corr-2", "REVOKE", 12L);

        ArgumentCaptor<DomainEventEnvelope> event =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AccessReviewWorkItemOutboxPublisher.DECIDED);
        assertThat(event.getValue().aggregateSequence()).isEqualTo(2L);
        assertThat(event.getValue().data().path("objectVersion").longValue()).isEqualTo(12L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void remediationObjectVersionBumpDoesNotCreateARevocationSequenceGap() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        ResultSet result = projection(
                UUID.randomUUID(), "REVOKE", "REVOKED", 3L, 2L);
        UUID ref = result.getObject("work_item_ref", UUID.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var publisher = publisher(jdbc, recorder);

        publisher.revoked(1L, ref, "corr-3", 3L);

        ArgumentCaptor<DomainEventEnvelope> event =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AccessReviewWorkItemOutboxPublisher.REVOKED);
        assertThat(event.getValue().aggregateSequence()).isEqualTo(3L);
        assertThat(event.getValue().data().path("objectVersion").longValue()).isEqualTo(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleResultingVersionCannotAdvanceTheEventSequence() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        ResultSet result = projection(
                UUID.randomUUID(), "APPROVE", "ACTIVE", 12L, 1L);
        UUID ref = result.getObject("work_item_ref", UUID.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        var publisher = publisher(jdbc, recorder);

        assertThatThrownBy(() -> publisher.decided(
                1L, ref, "corr-4", "APPROVE", 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resultingVersion");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(recorder, never()).record(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatingAssignmentPublicationDoesNotCreateASecondSequence() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        UUID campaignId = UUID.randomUUID();
        ResultSet result = projection(
                UUID.randomUUID(), "PENDING", "ACTIVE", 0L, 0L);
        AtomicInteger queryCount = new AtomicInteger();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(campaignId)))
                .thenAnswer(invocation -> {
                    if (queryCount.getAndIncrement() > 0) return List.of();
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var publisher = publisher(jdbc, recorder);

        publisher.assignedForCampaign(1L, campaignId, "corr-5");
        publisher.assignedForCampaign(1L, campaignId, "corr-5-retry");

        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        verify(recorder, times(1)).record(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateDecisionSequenceIsRejectedBeforeMutation() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        ResultSet result = projection(
                UUID.randomUUID(), "APPROVE", "ACTIVE", 12L, 2L);
        UUID ref = result.getObject("work_item_ref", UUID.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        var publisher = publisher(jdbc, recorder);

        assertThatThrownBy(() -> publisher.decided(
                1L, ref, "corr-6", "APPROVE", 12L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not contiguous");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(recorder, never()).record(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void foreignTenantProjectionCannotAdvanceOrRecordAnEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        UUID ref = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(2L), eq(ref)))
                .thenReturn(List.of());
        var publisher = publisher(jdbc, recorder);

        assertThatThrownBy(() -> publisher.decided(
                2L, ref, "corr-7", "APPROVE", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost its owner projection");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(recorder, never()).record(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void revocationBeforeAssignmentSequenceIsRejectedBeforeMutation() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        ResultSet result = projection(
                UUID.randomUUID(), "PENDING", "REVOKED", 1L, 0L);
        UUID ref = result.getObject("work_item_ref", UUID.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(ref)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(result, 0));
                });
        var publisher = publisher(jdbc, recorder);

        assertThatThrownBy(() -> publisher.revoked(1L, ref, "corr-8", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not contiguous");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(recorder, never()).record(any());
    }

    private AccessReviewWorkItemOutboxPublisher publisher(
            JdbcTemplate jdbc,
            DomainEventRecorder recorder) {
        return new AccessReviewWorkItemOutboxPublisher(
                jdbc,
                recorder,
                mock(DomainEventContractRegistry.class),
                new ObjectMapper().findAndRegisterModules());
    }

    private ResultSet projection(
            UUID ref,
            String decision,
            String assignmentState,
            long objectVersion,
            long eventSequence) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("work_item_ref", UUID.class)).thenReturn(ref);
        when(result.getLong("reviewer_user_id")).thenReturn(7L);
        when(result.getTimestamp("due_at")).thenReturn(
                Timestamp.from(Instant.parse("2026-08-25T03:00:00Z")));
        when(result.getString("decision")).thenReturn(decision);
        when(result.getString("reviewer_assignment_state")).thenReturn(assignmentState);
        when(result.getLong("version")).thenReturn(objectVersion);
        when(result.getLong("work_event_sequence")).thenReturn(eventSequence);
        return result;
    }
}
