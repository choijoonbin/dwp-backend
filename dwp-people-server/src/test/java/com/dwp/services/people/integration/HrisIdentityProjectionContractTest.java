package com.dwp.services.people.integration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HrisIdentityProjectionContractTest {

    @Test
    void projectionEventCarriesTheAuthoritativeWorkerNumberToAuth() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HrisIntegrationIngestionRepository repository =
                new HrisIntegrationIngestionRepository(jdbc);
        HrisModels.WorkerRecord worker = new HrisModels.WorkerRecord(
                "worker-external-1",
                "v1",
                "EMP-88219",
                "EMPLOYEE",
                "ACTIVE",
                "Employee",
                "Em",
                "Ployee",
                "ko-KR",
                "Asia/Seoul",
                "employee@example.com",
                null,
                null,
                List.of());

        repository.emitProjectionChanged(
                1L, UUID.randomUUID(), UUID.randomUUID(), "corr-1", worker, "Engineer");

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("'workerNumber', :workerNumber"), parameters.capture());
        assertThat(parameters.getValue().getValue("workerNumber")).isEqualTo("EMP-88219");
    }
}
