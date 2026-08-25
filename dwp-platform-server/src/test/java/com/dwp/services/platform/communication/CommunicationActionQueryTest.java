package com.dwp.services.platform.communication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommunicationActionQueryTest {

    @Mock
    private JdbcTemplate jdbc;

    private CommunicationActionQuery query;

    @BeforeEach
    void setUp() {
        query = new CommunicationActionQuery(jdbc);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readsExactGlobalCountsAndOnlyABoundedActionFirstIdSlice() {
        CommunicationDtos.FeedSummary summary =
                new CommunicationDtos.FeedSummary(210, 42, 3, 7, 2, 4);
        doReturn(summary).when(jdbc).queryForObject(
                anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(501L, 401L, 301L, 201L)).when(jdbc).query(
                anyString(), any(RowMapper.class), any(Object[].class));

        CommunicationActionQuery.ActionSnapshot result = query.snapshot(
                7L,
                11L,
                List.of("TENANT_ADMIN", "WORKSPACE_MEMBER"),
                OffsetDateTime.of(2026, 8, 24, 9, 0, 0, 0, ZoneOffset.UTC),
                80);

        assertThat(result.summary()).isEqualTo(summary);
        assertThat(result.actionableIds()).containsExactly(501L, 401L, 301L, 201L);

        ArgumentCaptor<String> summarySql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> summaryParameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(
                summarySql.capture(), any(RowMapper.class), summaryParameters.capture());
        assertThat(summarySql.getValue())
                .contains("COUNT(*) FILTER", "AS critical_unread", "AS actionable")
                .doesNotContain("LIMIT");
        assertThat(summaryParameters.getValue())
                .containsExactly(
                        11L,
                        7L,
                        OffsetDateTime.of(2026, 8, 24, 9, 0, 0, 0, ZoneOffset.UTC),
                        OffsetDateTime.of(2026, 8, 24, 9, 0, 0, 0, ZoneOffset.UTC),
                        "TENANT_ADMIN",
                        "WORKSPACE_MEMBER");

        ArgumentCaptor<String> actionSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> actionParameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(actionSql.capture(), any(RowMapper.class), actionParameters.capture());
        assertThat(actionSql.getValue())
                .contains(
                        "announcement.severity = 'CRITICAL'",
                        "announcement.acknowledgement_required = TRUE",
                        "ORDER BY CASE",
                        "LIMIT ?");
        assertThat(actionParameters.getValue()).endsWith(48);
    }
}
