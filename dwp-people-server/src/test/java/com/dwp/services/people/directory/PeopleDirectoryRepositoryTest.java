package com.dwp.services.people.directory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleDirectoryRepositoryTest {

    @Test
    void assignmentRegisterSearchIncludesAssignmentKey() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any()))
                .thenReturn(List.of());
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);

        repository.search(
                7L,
                0L,
                "ASG-MINA-PRIMARY",
                null,
                LocalDate.of(2026, 9, 2),
                51,
                true,
                Set.of());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());

        assertThat(sql.getValue().replaceAll("\\s+", " ").trim().toLowerCase())
                .contains("lower(coalesce(a.assignment_key, '')) like :query");
        assertThat(parameters.getValue().getValue("query"))
                .isEqualTo("%asg-mina-primary%");
    }
}
