package com.dwp.services.platform.experience;

import com.dwp.services.platform.support.CappedList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperienceRevisionStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void fetchesOneExtraRowAndSignalsThatTheMaximumPageIsPartial() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var rows = IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new ExperienceRevisionStore.ExperienceRevision(
                        (long) index, 7L, "HOME", (long) index, "SETTINGS_PUBLISHED",
                        null, null, null, null))
                .toList();
        when(jdbc.query(
                anyString(), any(RowMapper.class), eq(7L), eq("HOME"), eq(51)))
                .thenReturn(rows);
        ExperienceRevisionStore store = new ExperienceRevisionStore(jdbc, new ObjectMapper());

        CappedList<ExperienceRevisionStore.ExperienceRevision> result =
                store.list(7L, "HOME", 500);

        assertThat(result.items()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.limit()).isEqualTo(50);
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(7L), eq("HOME"), eq(51));
    }
}
