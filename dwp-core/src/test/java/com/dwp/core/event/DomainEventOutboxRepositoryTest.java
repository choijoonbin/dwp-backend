package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainEventOutboxRepositoryTest {

    @Test
    void claimCreatesAnOpaqueLeaseTokenForTheClaimingWorker() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventOutboxRepository repository = new DomainEventOutboxRepository(
                jdbc, new ObjectMapper());
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<DomainEventOutboxRepository.ClaimedEvent>>any()))
                .thenReturn(List.of());

        repository.claim("worker-a", 10, 30);

        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                statement.capture(),
                parameters.capture(),
                org.mockito.ArgumentMatchers
                        .<RowMapper<DomainEventOutboxRepository.ClaimedEvent>>any());
        assertThat(statement.getValue()).contains("lock_token = :leaseToken");
        assertThat(parameters.getValue().getValue("workerId")).isEqualTo("worker-a");
        assertThatCode(() -> UUID.fromString(
                parameters.getValue().getValue("leaseToken").toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseExpiredLeasesBindsPostgresTimestamp() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventOutboxRepository repository = new DomainEventOutboxRepository(
                jdbc, new ObjectMapper());
        Instant now = Instant.parse("2026-08-21T01:00:00Z");

        repository.releaseExpiredLeases(now);

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue().getValue("now"))
                .isEqualTo(Timestamp.from(now));
    }

    @Test
    void reclaimedLeaseAllowsOnlyCurrentOwnerToCompleteWhenWorkersRace()
            throws ExecutionException, InterruptedException {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventOutboxRepository repository = new DomainEventOutboxRepository(
                jdbc, new ObjectMapper());
        UUID outboxId = UUID.randomUUID();
        CyclicBarrier simultaneousCompletion = new CyclicBarrier(2);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    simultaneousCompletion.await(2, TimeUnit.SECONDS);
                    return currentOwnerChanged(invocation.getArgument(1));
                });

        CompletableFuture<Integer> staleWorker = CompletableFuture.supplyAsync(() ->
                repository.markPublished(
                        "worker-a", "lease-a", List.of(outboxId)));
        CompletableFuture<Integer> currentWorker = CompletableFuture.supplyAsync(() ->
                repository.markPublished(
                        "worker-b", "lease-b", List.of(outboxId)));

        assertThat(staleWorker.get()).isZero();
        assertThat(currentWorker.get()).isOne();
    }

    @Test
    void staleWorkerCannotOverwriteFailureAfterAnotherWorkerReclaimsLease() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventOutboxRepository repository = new DomainEventOutboxRepository(
                jdbc, new ObjectMapper());
        UUID outboxId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> currentOwnerChanged(invocation.getArgument(1)));

        boolean staleChanged = repository.markFailed(
                "worker-a", "lease-a", outboxId, 1, 5, "stale failure");
        boolean currentChanged = repository.markFailed(
                "worker-b", "lease-b", outboxId, 2, 5, "current failure");

        assertThat(staleChanged).isFalse();
        assertThat(currentChanged).isTrue();
    }

    @Test
    void completionAndFailureRequireAnUnexpiredSendingLease() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventOutboxRepository repository = new DomainEventOutboxRepository(
                jdbc, new ObjectMapper());
        UUID outboxId = UUID.randomUUID();

        repository.markPublished("worker", "lease", List.of(outboxId));
        repository.markFailed("worker", "lease", outboxId, 1, 5, "failed");

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                statements.capture(), any(MapSqlParameterSource.class));
        assertThat(statements.getAllValues()).allSatisfy(statement -> {
            assertThat(statement).contains("status = 'SENDING'");
            assertThat(statement).contains("locked_by = :workerId");
            assertThat(statement).contains("lock_token = :leaseToken");
            assertThat(statement).contains("locked_until >= CURRENT_TIMESTAMP");
        });
    }

    private int currentOwnerChanged(MapSqlParameterSource parameters) {
        return "worker-b".equals(parameters.getValue("workerId"))
                && "lease-b".equals(parameters.getValue("leaseToken"))
                ? 1
                : 0;
    }
}
