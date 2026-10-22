package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceIngestionReconciliationTest {
    @Test
    void processorAndRepositoryExposeOneSpringTransactionBoundaryWithTransactionalEffectPort()
            throws Exception {
        Method process = Hrm012WorkforceIngestionProcessor.class.getMethod(
                "process", long.class, java.util.UUID.class, String.class);
        Method reconcile = Hrm012WorkforceIngestionRepository.class.getMethod(
                "reconcileApply", Hrm012WorkforceIngestionRepository.Run.class,
                List.class, Hrm012WorkforceIngestionContract.Counts.class,
                String.class, String.class, String.class, java.time.Instant.class);
        Field effects = Hrm012WorkforceIngestionProcessor.class.getDeclaredField("effects");

        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                process, Transactional.class)).isNotNull();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                reconcile, Transactional.class)).isNotNull();
        assertThat(effects.getType()).isEqualTo(
                Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort.class);
    }

    @Test
    void processorFailsClosedBeforeClaimWhenNoActualTransactionExists() {
        Hrm012WorkforceIngestionRepository repository =
                mock(Hrm012WorkforceIngestionRepository.class);
        Hrm012WorkforceIngestionProcessor processor = new Hrm012WorkforceIngestionProcessor(
                repository, mock(Hrm012WorkforceIngestionPorts.ArtifactPort.class),
                mock(Hrm012WorkforceIngestionPorts.MappingPort.class),
                mock(Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort.class),
                mock(Hrm012WorkforceIngestionPorts.QuarantineProtectorPort.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Hrm012TestFixtures.NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> processor.process(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1"))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> {
                    Hrm012Problem problem = (Hrm012Problem) error;
                    assertThat(problem.status()).isEqualTo(503);
                    assertThat(problem.retryable()).isTrue();
                });
        verifyNoInteractions(repository);
    }

    @Test
    void applyReconciliationOrdersCursorAuditOutboxReceiptBeforeGuardedTerminalRun() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(Long.toString(Hrm012TestFixtures.TENANT));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        Hrm012WorkforceIngestionRepository repository =
                new Hrm012WorkforceIngestionRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        Hrm012WorkforceIngestionContract.ItemEvidence item =
                new Hrm012WorkforceIngestionContract.ItemEvidence(
                        1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                        Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED,
                        "d".repeat(64), null, null, "e".repeat(64),
                        null, null, null);
        Hrm012WorkforceIngestionContract.Counts counts =
                new Hrm012WorkforceIngestionContract.Counts(1, 1, 0, 0, 0, 1);

        repository.reconcileApply(
                Hrm012TestFixtures.run("RUNNING", null, null), List.of(item), counts,
                "f".repeat(64), "cursor-token", "9".repeat(64),
                Hrm012TestFixtures.NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(6)).update(sql.capture(), any(MapSqlParameterSource.class));
        List<String> writes = sql.getAllValues();
        assertThat(writes.get(0)).contains("INSERT INTO ppl_workforce_ingestion_items");
        assertThat(writes.get(1)).contains("INSERT INTO int_connector_cursors");
        assertThat(writes.get(2)).contains("INSERT INTO sys_people_audit_events");
        assertThat(writes.get(3)).contains("INSERT INTO sys_people_outbox_events");
        assertThat(writes.get(4)).contains("UPDATE ppl_command_receipts")
                .contains("lifecycle_state='SUCCEEDED'");
        assertThat(writes.get(5)).contains("UPDATE ppl_workforce_ingestion_runs")
                .contains("lifecycle_state='SUCCEEDED'")
                .contains("lifecycle_state='RUNNING'")
                .contains("version=:version");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimIsOneAtomicLeaseWinnerForReceivedRetryableOrExpiredRunningRun() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(Long.toString(Hrm012TestFixtures.TENANT));
        doReturn(List.of()).when(jdbc).query(anyString(),
                any(MapSqlParameterSource.class), any(RowMapper.class));
        Hrm012WorkforceIngestionRepository repository =
                new Hrm012WorkforceIngestionRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());

        Optional<Hrm012WorkforceIngestionRepository.Run> claimed = repository.claim(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RUN, "worker-1",
                Hrm012TestFixtures.NOW, Duration.ofMinutes(5));

        assertThat(claimed).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("UPDATE ppl_workforce_ingestion_runs")
                .contains("lifecycle_state='RECEIVED'")
                .contains("lifecycle_state='FAILED_RETRYABLE'")
                .contains("next_attempt_at<=:now")
                .contains("lifecycle_state='RUNNING'")
                .contains("lease_until<=:now")
                .contains("lease_owner=:worker")
                .contains("attempt_count=attempt_count+1")
                .contains("RETURNING *");
    }
}
