package com.dwp.services.people.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisIntegrationRepositoryWorkforceIdentityTest {

    @Test
    void failsClosedForMultipleCurrentPrimaryRelationshipLineages() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.<HrisIntegrationRepository.WorkforceIdentityProjection>query(
                anyString(),
                any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<
                        HrisIntegrationRepository.WorkforceIdentityProjection>>any()))
                .thenReturn(List.of(identity(10L), identity(11L)));
        HrisIntegrationRepository repository = new HrisIntegrationRepository(jdbc);

        assertThatThrownBy(() -> repository.findWorkforceIdentity(41L, "E100001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple current primary relationship lineages");
    }

    private HrisIntegrationRepository.WorkforceIdentityProjection identity(long workerId) {
        return new HrisIntegrationRepository.WorkforceIdentityProjection(
                workerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "WD-" + workerId,
                "E100001",
                "Actor",
                "Act",
                "Or",
                "actor@example.com",
                "Lead",
                "ko-KR",
                "ACTIVE",
                "v1");
    }
}
