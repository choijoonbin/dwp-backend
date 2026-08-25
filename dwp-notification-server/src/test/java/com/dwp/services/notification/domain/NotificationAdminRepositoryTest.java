package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAdminRepositoryTest {

    @Test
    void messagingNotificationsUseTheMessengerProductName() {
        assertThat(NotificationQueryRepository.appName("messaging")).isEqualTo("Messenger");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nullableContractFiltersAreBoundWithExplicitPostgresqlTypes() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        NotificationAdminRepository repository = new NotificationAdminRepository(jdbc);

        repository.typeContracts(1L, null, null, null, 0, 50);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));

        assertThat(sql.getValue())
                .contains("CAST(:query AS text) IS NULL")
                .contains("CAST(:state AS text) IS NULL")
                .contains("CAST(:appKey AS text) IS NULL");
        assertThat(params.getValue().getSqlType("query")).isEqualTo(Types.VARCHAR);
        assertThat(params.getValue().getSqlType("state")).isEqualTo(Types.VARCHAR);
        assertThat(params.getValue().getSqlType("appKey")).isEqualTo(Types.VARCHAR);
    }
}
