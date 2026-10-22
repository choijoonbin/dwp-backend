package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceIngestionEventOutboxTest {
    @Test
    void terminalApplyWritesOneDeterministicExactTenFieldBusinessEvent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(Long.toString(Hrm012TestFixtures.TENANT));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        Hrm012WorkforceIngestionRepository repository =
                new Hrm012WorkforceIngestionRepository(jdbc, mapper);
        var item = appliedItem();
        var counts = new Hrm012WorkforceIngestionContract.Counts(1, 1, 0, 0, 0, 1);

        repository.reconcileApply(
                Hrm012TestFixtures.run("RUNNING", null, null), List.of(item), counts,
                "f".repeat(64), "cursor-token", "9".repeat(64),
                Hrm012TestFixtures.NOW);
        repository.reconcileApply(
                Hrm012TestFixtures.run("RUNNING", null, null), List.of(item), counts,
                "f".repeat(64), "cursor-token", "9".repeat(64),
                Hrm012TestFixtures.NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(12)).update(sql.capture(), parameters.capture());
        List<MapSqlParameterSource> outbox = java.util.stream.IntStream
                .range(0, sql.getAllValues().size())
                .filter(index -> sql.getAllValues().get(index)
                        .contains("INSERT INTO sys_people_outbox_events"))
                .mapToObj(parameters.getAllValues()::get)
                .toList();
        assertThat(outbox).hasSize(2);
        assertThat(outbox.get(0).getValue("event"))
                .isEqualTo(outbox.get(1).getValue("event"))
                .isInstanceOf(UUID.class);
        assertThat(outbox.get(0).getValue("type"))
                .isEqualTo("WorkforceIngestionReconciled.v1");
        JsonNode payload = mapper.readTree((String) outbox.get(0).getValue("payload"));
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "runPublicId", "connectorPublicId", "mappingVersion",
                        "sourceCount", "acceptedCount", "rejectedCount",
                        "duplicateCount", "quarantinedCount", "appliedCount",
                        "resultDigest");
        assertThat(payload.size()).isEqualTo(10);
        assertThat(payload.toString()).doesNotContain(
                "objectRef", "objectDigest", "schemaKey", "sourceRecordKey",
                "payload", "ciphertext", "keyReference", "cursor");
    }

    @Test
    void validateOnlyWritesNeitherCursorNorReconciledEvent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(Long.toString(Hrm012TestFixtures.TENANT));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        Hrm012WorkforceIngestionRepository repository =
                new Hrm012WorkforceIngestionRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        Hrm012WorkforceIngestionRepository.Run apply =
                Hrm012TestFixtures.run("RUNNING", null, null);
        Hrm012WorkforceIngestionRepository.Run validate =
                new Hrm012WorkforceIngestionRepository.Run(
                        apply.runId(), apply.runPublicId(), apply.tenantId(), apply.receiptId(),
                        null, null, apply.connectorId(), apply.connectorPublicId(),
                        apply.connectorVersion(), apply.mappingId(), apply.mappingPublicId(),
                        apply.mappingVersion(), apply.mappingSha256(),
                        apply.legalEntityPublicIds(), apply.populationScopeDigest(),
                        apply.objectRef(),
                        apply.objectDigest(), apply.schemaKey(), apply.schemaVersion(),
                        Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY,
                        apply.effectiveDate(), apply.state(), apply.cursorBeforeDigest(),
                        apply.cursorExpectedVersion(), apply.version(), apply.actorPublicId(),
                        apply.correlationId(), apply.expectedObjectVersion(), apply.createdAt());
        var validated = new Hrm012WorkforceIngestionContract.ItemEvidence(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED,
                "d".repeat(64), null, null, null, null, null, null);

        repository.reconcileValidation(validate, List.of(validated),
                new Hrm012WorkforceIngestionContract.Counts(1, 0, 0, 0, 0, 0),
                "f".repeat(64), Hrm012TestFixtures.NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).update(sql.capture(), any(MapSqlParameterSource.class));
        assertThat(sql.getAllValues()).noneMatch(value ->
                value.contains("sys_people_outbox_events")
                        || value.contains("int_connector_cursors"));
    }

    private Hrm012WorkforceIngestionContract.ItemEvidence appliedItem() {
        return new Hrm012WorkforceIngestionContract.ItemEvidence(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED,
                "d".repeat(64), null, null, "e".repeat(64),
                null, null, null);
    }
}
