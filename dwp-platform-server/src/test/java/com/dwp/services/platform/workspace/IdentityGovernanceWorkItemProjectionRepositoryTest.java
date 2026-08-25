package com.dwp.services.platform.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityGovernanceWorkItemProjectionRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private IdentityGovernanceWorkItemProjectionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new IdentityGovernanceWorkItemProjectionRepository(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    void assignmentUsesTenantScopedIdAndOpaqueOwnerReferenceInTheSourceRoute() {
        UUID eventId = UUID.randomUUID();
        UUID workItemRef = UUID.randomUUID();

        assertThat(repository.assigned(
                11L, eventId, 1L, workItemRef, 7L,
                Instant.parse("2026-08-25T03:00:00Z"))).isTrue();

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), values.capture());
        assertThat(values.getValue()[0]).isEqualTo(
                IdentityGovernanceWorkItemProjectionRepository.projectionId(11L, workItemRef));
        assertThat(values.getValue()[1]).isEqualTo(11L);
        assertThat(values.getValue()[6]).isEqualTo(workItemRef.toString());
        assertThat(values.getValue()[7]).isEqualTo("/work/queue?item=" + workItemRef);
    }

    @Test
    void sameOpaqueReferenceProducesDifferentProjectionIdsForDifferentTenants() {
        UUID workItemRef = UUID.randomUUID();

        assertThat(IdentityGovernanceWorkItemProjectionRepository.projectionId(11L, workItemRef))
                .isNotEqualTo(IdentityGovernanceWorkItemProjectionRepository.projectionId(
                        12L, workItemRef));
    }

    @Test
    void decisionUpdateIsTenantOwnerAndSequenceScoped() {
        UUID eventId = UUID.randomUUID();
        UUID workItemRef = UUID.randomUUID();

        assertThat(repository.decided(11L, eventId, 2L, workItemRef, "APPROVE")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), values.capture());
        assertThat(sql.getValue())
                .contains("WHERE tenant_id = ? AND source_system = ? AND source_reference = ?")
                .contains("AND source_event_sequence < ?");
        assertThat(values.getValue()).containsSequence(
                eventId, 2L, 11L,
                IdentityGovernanceWorkItemProjectionRepository.SOURCE_SYSTEM,
                workItemRef.toString(), 2L);
    }
}
