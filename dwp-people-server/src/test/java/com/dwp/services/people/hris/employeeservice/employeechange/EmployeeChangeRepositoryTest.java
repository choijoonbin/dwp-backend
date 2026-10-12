package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmployeeChangeRepositoryTest {

    private static final long TENANT = 7L;
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    @Test
    void createPersistsCanonicalVersionOneWithoutChangingV54() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(Long.toString(TENANT));
        List<String> statements = new ArrayList<>();
        EmployeeChangeRepository.RequestRow inserted = draftRow();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<EmployeeChangeRepository.RequestRow>>any()))
                .thenAnswer(invocation -> {
                    statements.add(invocation.getArgument(0));
                    return List.of(inserted);
                });
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EmployeeChangeRepository repository = new EmployeeChangeRepository(jdbc, mapper);
        EmployeeChangeCanonicalizer canonicalizer = new EmployeeChangeCanonicalizer(mapper);
        EmployeeChangeDtos.CreateRequest request = createRequest();
        EmployeeChangeCanonicalizer.CanonicalPatch patch =
                canonicalizer.canonicalPatch(request);
        EmployeeChangeRepository.TargetRow target = new EmployeeChangeRepository.TargetRow(
                31L, 41L, uuid(31), "PERSON", uuid(32),
                0L, "Employee 31", LocalDate.of(2020, 1, 1), null);

        EmployeeChangeRepository.RequestRow created = repository.create(
                TENANT, uuid(21), 71L, uuid(11), target, patch,
                new EmployeeChangePatchProtector.ProtectedPatch(
                        new byte[] {1, 2, 3}, "test-key"),
                LocalDate.of(2026, 10, 12), "a".repeat(64), 11L, 13L,
                "b".repeat(64), uuid(51), 19L, List.of(), "HR_OPERATIONS");

        assertThat(created.version()).isEqualTo(1L);
        assertThat(normalized(statements.getFirst()))
                .contains("originating_context_digest,correlation_id,version,created_by,updated_by")
                .contains(":contextDigest,:correlation,1,:actor,:actor");
    }

    @Test
    void workbenchDoesNotTruncateTheGlobalCandidateSetBeforeProjection() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(Long.toString(TENANT));
        List<String> statements = new ArrayList<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<EmployeeChangeRepository.WorkbenchRow>>any()))
                .thenAnswer(invocation -> {
                    statements.add(invocation.getArgument(0));
                    return List.of();
                });
        EmployeeChangeRepository repository = new EmployeeChangeRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        91L, null, true, Set.of(), Set.of("EMPLOYMENT"), "policy:31");

        assertThat(repository.workbench(TENANT, NOW, scope)).isEmpty();

        assertThat(normalized(statements.getFirst()))
                .contains("request.created_at<=:asOf")
                .contains("ORDER BY request.public_id")
                .doesNotContain("LIMIT 500");
    }

    private EmployeeChangeRepository.RequestRow draftRow() {
        EmployeeChangeCanonicalizer canonicalizer = new EmployeeChangeCanonicalizer(
                new ObjectMapper().findAndRegisterModules());
        String digest = canonicalizer.canonicalPatch(createRequest()).digest();
        return new EmployeeChangeRepository.RequestRow(
                21L, uuid(21), 71L, null, 31L, null, 0L, null,
                "PERSON", uuid(32), "PROFILE_UPDATE", LocalDate.of(2026, 10, 12),
                "DRAFT", 0L, "employee-change.person.v1", 1,
                new byte[] {1, 2, 3}, digest, "test-key", List.of("PERSON_NAME"),
                null, null, null, null, null, null,
                "a".repeat(64), 11L, 13L, "b".repeat(64),
                null, uuid(51), 1L, NOW, 19L);
    }

    private EmployeeChangeDtos.CreateRequest createRequest() {
        return new EmployeeChangeDtos.CreateRequest(
                uuid(31), "PERSON", uuid(32), "PROFILE_UPDATE",
                LocalDate.of(2026, 10, 12), "employee-change.person.v1", 1,
                List.of(new EmployeeChangeDtos.PatchOperation(
                        "replace", "/displayName", "Updated name")), List.of());
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
