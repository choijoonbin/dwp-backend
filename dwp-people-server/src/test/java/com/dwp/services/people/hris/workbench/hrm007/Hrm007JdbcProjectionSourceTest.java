package com.dwp.services.people.hris.workbench.hrm007;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hrm007JdbcProjectionSourceTest {

    @Test
    void everyProjectionUsesOnlySelectAndAnExplicitTrustedTenantPredicate() {
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            when(jdbc.queryForObject(
                    anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                    .thenReturn("41");
            when(jdbc.query(
                    anyString(), any(MapSqlParameterSource.class),
                    org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                    .thenReturn(List.of());
            JdbcHrm007ProjectionSource source = new JdbcHrm007ProjectionSource(jdbc);
            Hrm007Authority.TargetPopulation population =
                    new Hrm007Authority.TargetPopulation(
                            definition.populations().iterator().next(), true, 11L,
                            null, Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"),
                            "population:17");

            source.read(definition, new Hrm007ProjectionSource.SourceRequest(
                    41L, Instant.parse("2026-10-17T12:00:00Z"),
                    LocalDate.parse("2026-10-17"), null, population));

            var tenantSql = org.mockito.ArgumentCaptor.forClass(String.class);
            var tenantParameters = org.mockito.ArgumentCaptor
                    .forClass(MapSqlParameterSource.class);
            verify(jdbc).queryForObject(
                    tenantSql.capture(), tenantParameters.capture(), eq(String.class));
            assertThat(tenantSql.getValue())
                    .isEqualTo("SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)");
            assertThat(tenantParameters.getValue().getValue("tenant")).isEqualTo(41L);

            var projectionSql = org.mockito.ArgumentCaptor.forClass(String.class);
            var projectionParameters = org.mockito.ArgumentCaptor
                    .forClass(MapSqlParameterSource.class);
            verify(jdbc).query(
                    projectionSql.capture(), projectionParameters.capture(),
                    org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
            assertThat(projectionSql.getValue().stripLeading()).startsWith("SELECT");
            assertThat(projectionSql.getValue()).contains("tenant_id=:tenant");
            assertThat(projectionSql.getValue())
                    .doesNotMatch("(?is).*\\b(?:INSERT|UPDATE|DELETE|MERGE)\\b.*");
            assertThat(projectionParameters.getValue().getValue("tenant")).isEqualTo(41L);
        }
    }

    @Test
    void iaq013ReadsTheExistingReasonCatalogWithoutSchemaMutation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("41");
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        JdbcHrm007ProjectionSource source = new JdbcHrm007ProjectionSource(jdbc);
        Hrm007Authority.TargetPopulation population =
                new Hrm007Authority.TargetPopulation(
                        "CONFIG_SCOPE", true, 0L, null, Set.of(),
                        Set.of("POLICY_METADATA", "EMPLOYMENT_RULE", "IMPACT_SUMMARY"),
                        "config:17");

        source.read(Hrm007QueryDefinition.PEOPLE_EMPLOYMENT,
                new Hrm007ProjectionSource.SourceRequest(
                        41L, Instant.parse("2026-10-17T12:00:00Z"),
                        LocalDate.parse("2026-10-17"), null, population));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
        assertThat(sql.getValue()).contains(
                "ppl_assignment_change_reason_catalog",
                "catalog.reason_code",
                "catalog.effective_start_date",
                "catalog.effective_end_date",
                "catalog.lifecycle_state",
                "catalog.version",
                "assignment.change_reason_code");
    }
}
